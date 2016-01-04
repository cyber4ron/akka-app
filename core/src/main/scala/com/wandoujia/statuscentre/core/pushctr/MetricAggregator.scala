package com.wandoujia.statuscentre.core.pushctr

import akka.actor._
import akka.contrib.pattern.{ ClusterSingletonManager, ClusterSingletonProxy }
import akka.pattern.ask
import akka.persistence._
import akka.util.Timeout
import com.wandoujia.statuscentre.core.pushctr.MetricAggregator._
import com.wandoujia.statuscentre.core.pushctr.PushCtr._
import com.wandoujia.statuscentre.core.tser.TSer.Freq.Freq
import com.wandoujia.statuscentre.core.tser.TSer.FreqTimeSpan
import org.slf4j.LoggerFactory

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

object MetricAggregator {

  val singletonManagerName = "singleton-manager"

  val aggregatorName = "metric-aggregator"
  val aggregatorPath = s"/user/$singletonManagerName/$aggregatorName"

  val aggregatorProxyName = "metric-aggregator-proxy"
  val aggregatorProxyPath = s"/user/$aggregatorProxyName"

  // command
  case class GetPushesByTime(fromTs: Long, toTs: Long, page: Int, pageSize: Int) // page从1开始
  case class GetRecentTopPushes(toTs: Long, freq: Freq, page: Int, pageSize: Int)
  case object GetAllPath

  // internal command
  private[pushctr] case class Update(ts: Long)
  private case object SaveSnapshot

  //persistence
  private case class StateUpdated(pushId: String, ts: Option[Long] = None)
  private final case class SnapshotState(tuples: Array[(String, Option[(Long, Long)])])

  def props(resolver: ActorRef) = Props(classOf[MetricAggregator], resolver)

  def startAggregator(system: ActorSystem, resolver: ActorRef) {
    system.actorOf(ClusterSingletonManager.props(
      singletonProps = props(resolver),
      singletonName = aggregatorName,
      terminationMessage = PoisonPill,
      role = None), // role ?
      name = singletonManagerName)
  }

  def startAggregatorProxy(system: ActorSystem) {
    system.actorOf(ClusterSingletonProxy.props(
      singletonPath = aggregatorPath,
      role = None), // ?
      name = aggregatorProxyName)
    // ClusterReceptionistExtension(system).registerService(proxy)
  }

  def proxy(system: ActorSystem) = system.actorSelection(aggregatorProxyPath)
}

class MetricAggregator(resolver: ActorRef) extends Actor with PersistentActor {
  private val log = LoggerFactory.getLogger(this.getClass)
  val scheduler = context.system.scheduler

  override def persistenceId: String = "metric-aggregator"

  import context.dispatcher

  implicit val askTimeout = Timeout(20.seconds)

  var pushCtrActors: HashMap[String, Option[(Long, Long)]] = HashMap.empty[String, Option[(Long, Long)]]

  val snapshotTask = Some(scheduler.schedule(10.seconds, 10.minutes, self, MetricAggregator.SaveSnapshot))

  def updatePushRange(pushId: String, ts: Option[Long]) {
    if (!pushCtrActors.contains(pushId)) {
      pushCtrActors = pushCtrActors.updated(pushId, None)
    }

    if (ts.isEmpty) return

    if (pushCtrActors.get(pushId).get.isEmpty) {
      pushCtrActors = pushCtrActors.updated(pushId, Some(ts.get, ts.get))
    } else {
      val (fromTs, toTs) = pushCtrActors.get(sender().path.name).get.get
      pushCtrActors = pushCtrActors.updated(pushId, Some((math.min(fromTs, ts.get), math.max(toTs, ts.get))))
    }
  }

  def receiveEvt: Receive = {
    case Join =>
      log.info(s"PushCtr Join MetricAggregator: ${sender().path}")
      context watch sender()
      persistAsync(StateUpdated(sender().path.name)) { evt =>
        updatePushRange(evt.pushId, evt.ts)
      }

    case MetricAggregator.Update(ts) =>
      persistAsync(StateUpdated(sender().path.name, Some(ts))) { evt =>
        updatePushRange(evt.pushId, evt.ts)
      }

    case Terminated(ref) =>
      log.info(s"PushCtr Terminated in MetricAggregator: ${sender().path}")
      context unwatch ref
  }

  def receiveCmd: Receive = {
    case GetPushesByTime(fromTs, toTs, page, pageSize) =>
      val fs = pushCtrActors filter { // filter掉不在时间区间内的
        case (_, Some(range)) => toTs > range._1 && fromTs <= range._2
        case _                => false
      } map { kv =>
        resolver ? FilterPushByTime(kv._1, fromTs, toTs)
      }

      val all = Await.result(Future.sequence(fs), askTimeout.duration).filter {
        case _: PushInfo => true
        case _           => false
      }.map(_.asInstanceOf[PushInfo]).toSeq

      sender() ! (all.slice((page - 1) * pageSize, page * pageSize), all.size)

    case GetRecentTopPushes(toTs, freq, page, pageSize) =>
      val fs = pushCtrActors filter {
        case (_, Some(range)) => toTs > range._1 && toTs - FreqTimeSpan.getTimeSpan(freq) <= range._2
        case _                => false
      } map { kv =>
        resolver ? CtrRangeSum(kv._1, toTs - FreqTimeSpan.getTimeSpan(freq), toTs)
      }

      val all = Await.result(Future.sequence(fs), askTimeout.duration).filter {
        case _: CtrRangeSumResult => true
        case _                    => false
      }.map(_.asInstanceOf[CtrRangeSumResult]).toSeq
        .filter(x => x.shows != 0 || x.clicks != 0)
        .sortWith(_.clicks > _.clicks)

      sender() ! (all.slice((page - 1) * pageSize, page * pageSize), all.size)

    case GetAllPath =>
      sender ! pushCtrActors.toArray

    case SaveSnapshot =>
      saveSnapshot(SnapshotState(pushCtrActors.toArray))
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, offeredSnapshot: MetricAggregator.SnapshotState) =>
      log.info("recover from snapshot: {}", offeredSnapshot.toString)
      pushCtrActors = HashMap[String, Option[(Long, Long)]](offeredSnapshot.tuples: _*)

    case StateUpdated(pushId, ts) =>
      updatePushRange(pushId, ts)

    case x: SnapshotOffer =>
      log.warn("Recovery received unknown")

    case RecoveryFailure(cause) =>
      log.error("Recovery failure: {}", cause.toString)

    case RecoveryCompleted =>
      log.info("Recovery completed: {}", self.path)
  }

  def receivePersistCommand: Receive = {
    case SaveSnapshotSuccess(meta) =>
      log.info("save snapshot succeeded: sequenceNr = {}", meta.sequenceNr)
      log.info("delete journals and snapshots before: {}", meta.sequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria.create(meta.sequenceNr - 1, Long.MaxValue)) // upper bound for a selected snapshot's sequence number, upper bound for a selected snapshot's timestamp
      deleteMessages(meta.sequenceNr - 1)

    case f: PersistenceFailure =>
      log.error("persist failed: {}", f)

    case f: SaveSnapshotFailure =>
      log.error("save snapshot failed: {}", f)
  }

  override def receiveCommand = receiveEvt orElse receiveCmd orElse receivePersistCommand
}
