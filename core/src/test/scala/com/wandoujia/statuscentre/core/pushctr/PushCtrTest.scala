package com.wandoujia.statuscentre.core.pushctr

import java.util.GregorianCalendar

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.wandoujia.statuscentre.core.pushctr.PushCtr._
import com.wandoujia.statuscentre.core.tser.TSer.Freq

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

object PushCtrTest extends App {
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

  val supervisor = system.actorOf(PushCtrSupervisor.props)

  val rand = new Random()
  val pushId = rand.nextLong().toString
  val pushCtr = system.actorOf(PushCtr.props(supervisor), pushId)

  val ts1 = new GregorianCalendar(2015, 11, 5, 10, 20, 12).getTime.getTime
  val ts2 = new GregorianCalendar(2015, 11, 5, 11, 20, 12).getTime.getTime

  pushCtr ! PushEvent(pushId, "some title", 10, 2, ts1, System.currentTimeMillis)
  pushCtr ! PushEvent(pushId, "some title", 10, 2, ts2, System.currentTimeMillis)
  Thread.sleep(1000)

  // pushCtr ! KillActor // actor在terminate后需要persistenceActor.preStart中recover

  implicit val askTimeout = Timeout(5.seconds)
  val ctrUntilNow = Await.result(pushCtr ? CtrUntilNow(pushId), askTimeout.duration)
  assert(ctrUntilNow == CtrResult("some title", 20, ts1, ts2, 4, ts1, ts2), ctrUntilNow)

  val tsFrom = new GregorianCalendar(2015, 11, 5, 9, 20, 12).getTime.getTime
  val tsEnd = new GregorianCalendar(2015, 11, 5, 12, 20, 12).getTime.getTime
  val ctrRangeHour = Await.result(pushCtr ? CtrRange(pushId, tsFrom, tsEnd, Freq.HOUR), askTimeout.duration)
  assert(ctrRangeHour.asInstanceOf[CtrRangeResult].series.length == 2, ctrRangeHour)

  val tsFrom2 = new GregorianCalendar(2015, 11, 4, 9, 20, 12).getTime.getTime
  val tsEnd2 = new GregorianCalendar(2015, 11, 6, 12, 20, 12).getTime.getTime
  val ctrRangeDay = Await.result(pushCtr ? CtrRange(pushId, tsFrom2, tsEnd2, Freq.DAY), askTimeout.duration)
  assert(ctrRangeDay.asInstanceOf[CtrRangeResult].series.length == 1, ctrRangeDay)

  val tsFrom3 = new GregorianCalendar(2015, 11, 5, 10, 20, 22).getTime.getTime
  val tsEnd3 = new GregorianCalendar(2015, 11, 5, 11, 20, 12).getTime.getTime
  val ctrRangeHour3 = Await.result(pushCtr ? CtrRange(pushId, tsFrom3, tsEnd3, Freq.HOUR), askTimeout.duration)
  assert(ctrRangeHour3.asInstanceOf[CtrRangeResult].series.length == 1, ctrRangeHour3)

  val tsFrom4 = new GregorianCalendar(2015, 11, 5, 11, 20, 12).getTime.getTime
  val tsEnd4 = new GregorianCalendar(2015, 11, 5, 15, 20, 12).getTime.getTime
  val ctrRangeHour4 = Await.result(pushCtr ? CtrRange(pushId, tsFrom4, tsEnd4, Freq.HOUR), askTimeout.duration)
  assert(ctrRangeHour4.asInstanceOf[CtrRangeResult].series.isEmpty, ctrRangeHour4)

  val tsFrom5 = new GregorianCalendar(2015, 11, 5, 10, 20, 22).getTime.getTime
  val tsEnd5 = new GregorianCalendar(2015, 11, 5, 11, 20, 12).getTime.getTime
  val ctrRangeSum = Await.result(pushCtr ? CtrRangeSum(pushId, tsFrom5, tsEnd5), askTimeout.duration)
  assert(ctrRangeSum.asInstanceOf[CtrRangeSumResult].shows == 0 && ctrRangeSum.asInstanceOf[CtrRangeSumResult].clicks == 0, ctrRangeSum)

  val tsFrom6 = new GregorianCalendar(2015, 11, 5, 10, 20, 22).getTime.getTime
  val tsEnd6 = new GregorianCalendar(2015, 11, 5, 11, 20, 13).getTime.getTime
  val ctrRangeSum2 = Await.result(pushCtr ? CtrRangeSum(pushId, tsFrom6, tsEnd6), askTimeout.duration)
  assert(ctrRangeSum2.asInstanceOf[CtrRangeSumResult].shows == 10 && ctrRangeSum2.asInstanceOf[CtrRangeSumResult].clicks == 2, ctrRangeSum2)

  Thread.sleep(1000 * 1000)
}
