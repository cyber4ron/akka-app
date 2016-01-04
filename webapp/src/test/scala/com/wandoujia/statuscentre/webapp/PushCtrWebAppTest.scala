package com.wandoujia.statuscentre.webapp

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.wandoujia.statuscentre.core.pushctr.PushCtr.PushEvent
import com.wandoujia.statuscentre.core.pushctr.{ MetricAggregator, PushCtr, PushCtrSupervisor }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

import scala.concurrent.Future
import scala.util.Random

object PushCtrWebAppTest extends App with WordSpecLike with Matchers with BeforeAndAfterAll with Directives {
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
  implicit val system = ActorSystem("PushCtrTest", config)

  val pushCtrSupervisor = system.actorOf(PushCtrSupervisor.props)
  ClusterSharding(system).start(PushCtr.shardName, Some(PushCtr.props(pushCtrSupervisor)), PushCtr.idExtractor, PushCtr.shardResolver)
  val pushCtrResolver = ClusterSharding(system).shardRegion(PushCtr.shardName)

  val pushId = new Random().nextLong().toString
  val ts = System.currentTimeMillis
  println("===> " + pushId)
  println("===> " + ts)
  pushCtrResolver ! PushEvent(pushId, "some title", 10, 2, ts, ts)

  MetricAggregator.startAggregator(system, pushCtrResolver)
  MetricAggregator.startAggregatorProxy(system)
  val aggregator = MetricAggregator.proxy(system)

  val route = new PushCtrRoute4Odin(system, pushCtrResolver, aggregator).route ~ new PushCtrRoute(system, pushCtrResolver, aggregator).route
  val pushCtrRoute = Directives.respondWithHeader(RawHeader("Access-Control-Allow-Origin", "*")) {
    route
  }

  val webHost = "127.0.0.1"
  val webPort = 8083

  implicit val materializer = ActorMaterializer()
  val source = Http().bind(interface = webHost, port = webPort)
  source.runForeach { conn =>
    conn.handleWith(pushCtrRoute)
  }

  Thread.sleep(1000 * 1000L)

  import scala.concurrent.ExecutionContext.Implicits.global
  Future {
    Thread.sleep(5000)
    val addr = s"$webHost:$webPort"

    var url = s"http://$addr/pushCtr/getCtr?pushId=$pushId"
    println(url)
    println(scala.io.Source.fromURL(url).mkString)

    url = s"http://$addr/pushCtr/getCtrRangeSum?pushId=$pushId&fromTs=0&toTs=${Long.MaxValue}"
    println(url)
    println(scala.io.Source.fromURL(url).mkString)

    url = s"http://$addr/pushCtr/getCtrRange?pushId=$pushId&fromTs=0&toTs=${Long.MaxValue}&freq=sec"
    println(url)
    println(scala.io.Source.fromURL(url).mkString)

    url = s"http://$addr/pushCtr/getPush?pushId=$pushId"
    println(url)
    println(scala.io.Source.fromURL(url).mkString)

    url = s"http://$addr/pushCtr/getPushesByTime?pushId=$pushId&fromTs=0&toTs=${Long.MaxValue}"
    println(url)
    println(scala.io.Source.fromURL(url).mkString)

    url = s"http://$addr/pushCtr/getRecentTopPushes?toTs=${System.currentTimeMillis}&range=day"
    println(url)
    println(scala.io.Source.fromURL(url).mkString)

    url = s"http://$addr/pushCtr/allPushPath"
    println(url)
    println(scala.io.Source.fromURL(url).mkString)
  }
}
