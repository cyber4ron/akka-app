package com.wandoujia.statuscentre.core.pushctr

import java.util.GregorianCalendar

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.wandoujia.statuscentre.core.pushctr.MetricAggregator.{ GetAllPath, GetRecentTopPushes, GetPushesByTime }
import com.wandoujia.statuscentre.core.pushctr.PushCtr._
import com.wandoujia.statuscentre.core.tser.TSer.Freq

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

object AggregatorTest extends App {

  val conf =
    """
          |akka {
          |  loglevel = INFO
          |  stdout-loglevel = INFO
          |  loggers = ["akka.event.slf4j.Slf4jLogger"]
          |  event-handlers = ["akka.event.Logging$DefaultLogger"]
          |  actor {
          |    provider = "akka.cluster.ClusterActorRefProvider"
          |  }
          |
          |  remote {
          |    enabled-transports = ["akka.remote.netty.tcp"]
          |    log-remote-lifecycle-events = off
          |    netty.tcp {
          |      hostname = "127.0.0.1"
          |      port = 2551
          |    } }
          |
          |  cluster {
          |    seed-nodes = ["akka.tcp://PushCtrTest@127.0.0.1:2551"]
          |    roles = ["entity"]
          |  }
          |
          |  persistence.journal.leveldb.dir = "target/journal"
          |  persistence.snapshot-store.local.dir = "target/snapshots"
          |}
          |""".stripMargin

  val config = ConfigFactory.parseString(conf)
  val system = ActorSystem("PushCtrTest", config)

  val pushCtrSupervisor = system.actorOf(PushCtrSupervisor.props)
  ClusterSharding(system).start(PushCtr.shardName, Some(PushCtr.props(pushCtrSupervisor)), PushCtr.idExtractor, PushCtr.shardResolver)
  val pushCtrResolver = ClusterSharding(system).shardRegion(PushCtr.shardName)

  MetricAggregator.startAggregator(system, pushCtrResolver)
  MetricAggregator.startAggregatorProxy(system)
  val aggrProxy = MetricAggregator.proxy(system)

  val rand = new Random()
  val pushId = rand.nextLong().toString

  val ts1 = new GregorianCalendar(2015, 11, 5, 10, 20, 12).getTime.getTime
  pushCtrResolver ! PushEvent(pushId, "some title", 10, 2, ts1, System.currentTimeMillis)

  val ts2 = new GregorianCalendar(2015, 11, 5, 10, 20, 32).getTime.getTime
  pushCtrResolver ! PushEvent(pushId, "some title", 10, 2, ts2, System.currentTimeMillis)

  implicit val askTimeout = Timeout(5.seconds)
  val f = pushCtrResolver ? CtrUntilNow(pushId)
  val res = Await.result(f, askTimeout.duration)

  val f1 = pushCtrResolver ? CtrUntilNow(pushId)
  val res1 = Await.result(f, askTimeout.duration)

  val f2 = pushCtrResolver ? CtrUntilNow("123")
  val res2 = Await.result(f2, askTimeout.duration)

  val f3 = aggrProxy ? GetPushesByTime(0L, Long.MaxValue, 1, 20)
  val res3 = Await.result(f3, askTimeout.duration)
  assert(res3.asInstanceOf[Array[PushInfo]].length == 1, res3)

  val f31 = aggrProxy ? GetPushesByTime(new GregorianCalendar(2015, 11, 5, 10, 20, 22).getTime.getTime, new GregorianCalendar(2015, 11, 5, 10, 20, 32).getTime.getTime, 1, 20)
  val res31 = Await.result(f31, askTimeout.duration)
  assert(res31.asInstanceOf[Array[PushInfo]].length == 1, res31)

  val f32 = aggrProxy ? GetPushesByTime(new GregorianCalendar(2015, 11, 5, 10, 20, 33).getTime.getTime, new GregorianCalendar(2015, 11, 5, 10, 20, 42).getTime.getTime, 1, 20)
  val res32 = Await.result(f32, askTimeout.duration)
  assert(res32.asInstanceOf[Array[PushInfo]].length == 0, res32)

  val f33 = aggrProxy ? GetPushesByTime(new GregorianCalendar(2015, 11, 5, 9, 20, 10).getTime.getTime, new GregorianCalendar(2015, 11, 5, 10, 20, 10).getTime.getTime, 1, 20)
  val res33 = Await.result(f33, askTimeout.duration)
  assert(res33.asInstanceOf[Array[PushInfo]].length == 0, res33)

  val f4 = aggrProxy ? GetRecentTopPushes(System.currentTimeMillis, Freq.HOUR, 1, 20)
  val res4 = Await.result(f4, askTimeout.duration)
  assert(res4.asInstanceOf[Array[CtrRangeSumResult]].length == 0, res4)

  val f41 = aggrProxy ? GetRecentTopPushes(new GregorianCalendar(2015, 11, 5, 11, 20, 22).getTime.getTime, Freq.HOUR, 1, 20)
  val res41 = Await.result(f41, askTimeout.duration)
  assert(res41.asInstanceOf[Array[CtrRangeSumResult]].length == 1, res41)

  val f42 = aggrProxy ? GetRecentTopPushes(new GregorianCalendar(2015, 11, 5, 11, 20, 22).getTime.getTime, Freq.MINUTE, 1, 20)
  val res42 = Await.result(f42, askTimeout.duration)
  assert(res42.asInstanceOf[Array[CtrRangeSumResult]].length == 0, res42)

  val f43 = aggrProxy ? GetRecentTopPushes(new GregorianCalendar(2015, 11, 5, 11, 20, 32).getTime.getTime, Freq.SECOND, 1, 20)
  val res43 = Await.result(f43, askTimeout.duration)
  assert(res43.asInstanceOf[Array[CtrRangeSumResult]].length == 0, res43)

  val f5 = aggrProxy ? GetAllPath
  val res5 = Await.result(f5, askTimeout.duration)

  Thread.sleep(1000 * 1000)
}
