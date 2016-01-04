package com.wandoujia.statuscentre.core.pushctr

import java.util.{ TimerTask, Timer }

import akka.actor.{ Actor, ActorRef, PoisonPill, Props }
import akka.contrib.pattern.ShardRegion
import akka.persistence._
import com.wandoujia.statuscentre.core.pushctr.Models.{ Command, Event }
import com.wandoujia.statuscentre.core.pushctr.PushCtr.{ KillActor, _ }
import com.wandoujia.statuscentre.core.tser.TSer
import com.wandoujia.statuscentre.core.tser.TSer.Freq
import com.wandoujia.statuscentre.core.tser.TSer.Freq.Freq
import org.slf4j.LoggerFactory

import scala.collection.immutable
import scala.collection.immutable.TreeMap
import scala.concurrent.duration._

object PushCtr {
  private val log = LoggerFactory.getLogger(classOf[PushCtr])

  val shardName: String = "pushCtr"
  val shardNumMod = 100

  val supportedFreq: Seq[Freq] = Array(Freq.SECOND, Freq.MINUTE, Freq.HOUR, Freq.DAY).sorted

  lazy val idExtractor: ShardRegion.IdExtractor = {
    case evt: Event   => (evt.id, evt)
    case cmd: Command => (cmd.id, cmd)
  }

  lazy val shardResolver: ShardRegion.ShardResolver = {
    case cmd: Command => (cmd.id.hashCode % shardNumMod).toString
    case evt: Event   => (evt.id.hashCode % shardNumMod).toString
  }

  def props(supervisor: ActorRef) = Props(classOf[PushCtr], supervisor)

  // monitor
  private[pushctr] case object Join

  // event
  final case class PushEvent(override val id: String, title: String, shows: Int, clicks: Int,
                             clientTime: Long, serverTime: Long) extends Event

  // external command
  final case class CtrUntilNow(override val id: String) extends Command
  final case class CtrRange(override val id: String, fromMs: Long, toMs: Long, freq: Freq = supportedFreq.last) extends Command
  final case class CtrRangeSum(override val id: String, fromMs: Long, toMs: Long) extends Command

  final case class GetPush(override val id: String, freq: Option[Freq] = Some(supportedFreq.last)) extends Command
  final case class FilterPushByTime(override val id: String, fromTs: Long, toTs: Long) extends Command

  final case class KillActor(override val id: String) extends Command
  final case class SetByRequest(override val id: String, title: String, shows: Int, clicks: Int, ts: Long, freq: Freq) extends Command

  // internal command
  private case object SaveSnapshot
  private case object TryPassivate

  // persistence
  private final case class SnapshotState(title: String, shows: immutable.TreeMap[Freq.Value, TSer], clicks: immutable.TreeMap[Freq.Value, TSer])
  private final case class StateUpdated(shows: Int, clicks: Int, clientTime: Long)
  private final case class StateBatchUpdated(shows: immutable.TreeMap[Freq.Value, TSer], clicks: immutable.TreeMap[Freq.Value, TSer])

  // DTO
  final case class CtrResult(title: String, shows: Int, showFromTs: Long, showToTs: Long, clicks: Int, clickFromTs: Long, clickToTs: Long)
  final case class CtrRangeResult(title: String, series: Seq[(Long, Int, Int)])
  final case class PushInfo(pushId: String, pushTitle: String, series: Option[Seq[(Long, Int, Int)]] = None, freq: Option[Freq] = None)
  final case class CtrRangeSumResult(id: String, pushTitle: String, shows: Int, clicks: Int)
  final case class Info(id: String, info: Option[String] = None)
  final case class Error(id: String, reason: Option[String] = None)

  @volatile var logCounter = 0
  @volatile var qps = 0

  val t = new Timer()
  val task = new TimerTask {
    def run() {
      log.info(s"qps: $qps")
      qps = 0
    }
  }

  t.schedule(task, 0, 1000)
}

class PushCtr(supervisor: ActorRef) extends Actor with PersistentActor {
  private val log = LoggerFactory.getLogger(this.getClass)

  import context.dispatcher
  val scheduler = context.system.scheduler

  val pushId = self.path.name
  var pushTitle: String = _

  val metricAggregator = MetricAggregator.proxy(context.system)

  val persistenceId = s"${PushCtr.shardName}-${self.path.name}"

  val showTSers = TreeMap[Freq.Value, TSer](supportedFreq map { freq => freq -> TSer(freq) }: _*)
  val clickTSers = TreeMap[Freq.Value, TSer](supportedFreq map { freq => freq -> TSer(freq) }: _*)

  private var isUpdated = false

  val snapshotTask = Some(scheduler.schedule(10 seconds, 10 minutes, self, SaveSnapshot))
  val passivateTask = Some(scheduler.schedule(1 hour, 1 hour, self, TryPassivate))

  override def preStart(): Unit = {
    super[PersistentActor].preStart()
    supervisor ! Join
    metricAggregator ! Join
  }

  override def postStop(): Unit = {
    super[PersistentActor].postStop()
    snapshotTask foreach {
      _.cancel()
    }
    snapshotTask foreach {
      _.cancel()
    }
  }

  def exist() = pushTitle != null

  override def receiveCommand = receivePersistCommand orElse receiveEvt orElse receiveCmd

  def receiveEvt: Receive = {
    case PushEvent(pid, title, shows, clicks, tsClt, tsSvr) =>
      qps += 1
      if (pushTitle == null) pushTitle = title
      persistAsync(StateUpdated(shows, clicks, tsClt)) { evt => // title只snapshot
        if (logCounter % 100 == 0) {
          log.info(s"pushCtr event delay: ${System.currentTimeMillis() - tsSvr}ms")
          logCounter = 0
        }

        logCounter += 1

        if (shows != 0)
          showTSers foreach {
            case (freq, tSer) => tSer.acc(tsClt, shows)
          }

        if (clicks != 0)
          clickTSers foreach {
            case (freq, tSer) => tSer.acc(tsClt, clicks)
          }

        metricAggregator ! MetricAggregator.Update(tsClt)
        isUpdated = true
      }
  }

  def receiveCmd: Receive = {
    case CtrUntilNow(pid) => // 到目前为止的Ctr(一并给出统计的起止时间)
      if (!exist()) sender() ! Error(pushId, Some("pushId not exist."))
      else {
        val freq = Freq.SECOND
        if (showTSers.get(freq).isEmpty || clickTSers.get(freq).isEmpty) sender() ! Error(pushId, Some(s"$freq not supported."))
        else {
          val showTSer = showTSers.get(freq).get
          val clickTSer = clickTSers.get(freq).get
          sender() ! CtrResult(pushTitle, showTSer.sum(), showTSer.fromTs, showTSer.toTs, clickTSer.sum(), clickTSer.fromTs, clickTSer.toTs)
        }
      }

    case CtrRangeSum(pid, fromTs, toTs) => // 起始到终止时间段的shows和clicks
      if (!exist()) sender() ! Error(pushId, Some("pushId not exist."))
      else {
        val freq = Freq.SECOND
        if (showTSers.get(freq).isEmpty || clickTSers.get(freq).isEmpty) sender() ! Error(pushId, Some(s"$freq not supported."))
        else {
          if (!(showTSers.get(supportedFreq.head).get.overlap(fromTs, toTs)
            || clickTSers.get(supportedFreq.head).get.overlap(fromTs, toTs)))
            sender() ! Info(pushId, Some(s"push has no action in [$fromTs, $toTs)"))
          val shows = showTSers.get(freq).get.sumRange(fromTs, toTs)
          val clicks = clickTSers.get(freq).get.sumRange(fromTs, toTs)
          sender() ! CtrRangeSumResult(pushId, pushTitle, shows, clicks)
        }
      }

    case CtrRange(pid, from, to, freq) => // 起始到终止时间段shows/clicks频率为freq的时间序列
      if (!exist()) sender() ! Error(pushId, Some("pushId not exist."))
      else {
        if (showTSers.get(freq).isEmpty || clickTSers.get(freq).isEmpty) sender() ! Error(pushId, Some(s"$freq not supported."))
        else {
          val shows: TreeMap[Long, Int] = showTSers.get(freq).get.range(from, to)
          val clicks: TreeMap[Long, Int] = clickTSers.get(freq).get.range(from, to)
          sender() ! CtrRangeResult(pushTitle, TSer.zip(shows, clicks))
        }
      }

    case FilterPushByTime(pid, fromTs, toTs) => // 起始到终止时间段内有show/click事件的push
      if (!exist()) sender() ! Error(pushId, Some("pushId not exist."))
      else {
        if (showTSers.get(supportedFreq.last).get.overlap(fromTs, toTs)
          || clickTSers.get(supportedFreq.last).get.overlap(fromTs, toTs))
          sender() ! PushInfo(pushId, pushTitle)
        else sender() ! Info(pushId, Some(s"push has no action in [$fromTs, $toTs)"))
      }

    case GetPush(pid, freq) => // push信息，包括时间序列
      if (!exist()) sender() ! Error(pushId, Some("pushId not exist."))
      else {
        if (showTSers.get(freq.get).isEmpty || clickTSers.get(freq.get).isEmpty) sender() ! Error(pushId, Some(s"$freq not supported."))
        else {
          val series = TSer.zip(showTSers.get(freq.get).get, clickTSers.get(freq.get).get)
          sender() ! PushInfo(pushId, pushTitle, series, freq)
        }
      }

    case SetByRequest(id, title, shows, clicks, ts, freq) =>
      if (showTSers.get(freq).isEmpty || clickTSers.get(freq).isEmpty) sender() ! Error(pushId, Some(s"$freq not supported."))
      else {
        showTSers.get(freq).get.set(ts, shows)
        clickTSers.get(freq).get.set(ts, shows)
        sender() ! Info(pushId, Some("done."))
      }

    case KillActor =>
      log.info(s"kill by request: ${self.path}")
      self ! PoisonPill

    case SaveSnapshot =>
      if (isUpdated) {
        saveSnapshot(SnapshotState(pushTitle, showTSers, clickTSers))
        isUpdated = false
      }

    case TryPassivate =>
      if (!isUpdated) self ! PoisonPill
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

  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, offeredSnapshot: SnapshotState) =>
      log.info("recover from snapshot: {}", offeredSnapshot.toString)
      pushTitle = offeredSnapshot.title
      offeredSnapshot.shows foreach {
        case (freq, shows) => showTSers.get(freq).get.merge(shows)
      }
      offeredSnapshot.clicks foreach {
        case (freq, clicks) => clickTSers.get(freq).get.merge(clicks)
      }

    case pushCtrEvent: StateUpdated =>
      showTSers foreach {
        case (_, tSer) => tSer.acc(pushCtrEvent.clientTime, pushCtrEvent.shows)
      }
      clickTSers foreach {
        case (_, tSer) => tSer.acc(pushCtrEvent.clientTime, pushCtrEvent.clicks)
      }

    case x: SnapshotOffer =>
      log.warn("Recovery received unknown")

    case RecoveryFailure(cause) =>
      log.error("Recovery failure: {}", cause.toString)

    case RecoveryCompleted =>
      log.info("Recovery completed: {}", self.path)
  }
}
