package com.wandoujia.statuscentre.benchmark

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.wandoujia.statuscentre.core.pushctr.PushCtr.PushEvent
import com.wandoujia.statuscentre.core.pushctr.{ MetricAggregator, PushCtr, PushCtrSupervisor }
import com.wandoujia.statuscentre.webapp.PushCtrRoute
import org.slf4j.LoggerFactory

import scala.collection.immutable.HashMap

object StringParam {
  def unapply(str: String): String = str
}

object ServiceBenchMark extends App {

  var kv = HashMap.empty[String, String]

  def parse(args: List[String]): Unit = args match {
    case ("--targetQps" | "-t") :: StringParam(value) :: tail =>
      // kv = kv.updated("targetQps", value)
      parse(tail)
    case Nil =>
    case _   => println("parse args error.")
  }

  val targetQps = kv.getOrElse("targetQps", "500").toInt

  @volatile var remainReq = targetQps

  val t = new java.util.Timer()
  val task = new java.util.TimerTask {
    def run() {
      remainReq = targetQps
      println("===> " + remainReq)
    }
  }
  t.schedule(task, 0L, 1000L)

  val log = LoggerFactory.getLogger(ServiceBenchMark.getClass)

  implicit val system = ActorSystem("PushCtrTest", ConfigFactory.parseString("""
                                                                                        |akka {
                                                                                        |  loglevel = INFO
                                                                                        |  stdout-loglevel = INFO
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
                                                                                        |      port = 2554
                                                                                        |    } }
                                                                                        |
                                                                                        |  cluster {
                                                                                        |    seed-nodes = ["akka.tcp://PushCtrTest@127.0.0.1:2554"]
                                                                                        |    roles = ["entity"]
                                                                                        |  }
                                                                                        |
                                                                                        |  persistence.journal.leveldb.dir = "target/journal"
                                                                                        |  persistence.snapshot-store.local.dir = "target/snapshots"
                                                                                        |}
                                                                                        |
                                                                                      """.stripMargin))
  val pushCtrSupervisor = system.actorOf(PushCtrSupervisor.props)

  ClusterSharding(system).start(PushCtr.shardName, Some(PushCtr.props(pushCtrSupervisor)), PushCtr.idExtractor, PushCtr.shardResolver)

  val pushCtrResolver = ClusterSharding(system).shardRegion(PushCtr.shardName)

  MetricAggregator.startAggregator(system, pushCtrResolver)
  MetricAggregator.startAggregatorProxy(system)
  val aggregator = MetricAggregator.proxy(system)

  val route = new PushCtrRoute(system, pushCtrResolver, aggregator).routes
  val pushCtrRoute = Directives.respondWithHeader(RawHeader("Access-Control-Allow-Origin", "*"))(route)

  implicit val materializer = ActorMaterializer()
  val source = Http().bind(interface = "127.0.0.1", port = 8083)
  source.runForeach(_.handleWith(pushCtrRoute))

  var pushIds = Vector.empty[Long]
  val rand = scala.util.Random
  (1 to 10).foreach(_ => pushIds = pushIds :+ rand.nextLong)

  while (true) {
    if (remainReq > 0) {
      val id = pushIds(remainReq % pushIds.size).toString
      pushCtrResolver ! PushEvent(id, "some title", 10, 1, System.currentTimeMillis, System.currentTimeMillis)
      remainReq -= 1
      Thread.sleep(1)
    }
  }

}
