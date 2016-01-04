package com.wandoujia.statuscentre

import java.util.GregorianCalendar

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.wandoujia.statuscentre.core.pushctr.PushCtr.PushEvent
import com.wandoujia.statuscentre.core.pushctr.{ PushCtr, PushCtrSupervisor }
import com.wandoujia.statuscentre.webapp.PushCtrRoute4Odin
import org.slf4j.LoggerFactory

object PushStandaloneServerTest extends App {

  val log = LoggerFactory.getLogger(PushStandaloneServer.getClass)

  implicit val system = ActorSystem("PushStandaloneTest", ConfigFactory.parseString("""
                                                                                     |akka {
                                                                                     |  loglevel = INFO
                                                                                     |  stdout-loglevel = INFO
                                                                                     |  event-handlers = ["akka.event.Logging$DefaultLogger"]
                                                                                     |  actor {
                                                                                     |    provider = "akka.cluster.ClusterActorRefProvider"
                                                                                     |  }
                                                                                     |
                                                                                     |  remote {
                                                                                     |    log-remote-lifecycle-events = off
                                                                                     |    netty.tcp {
                                                                                     |      hostname = "127.0.0.1"
                                                                                     |      port = 2551
                                                                                     |    } }
                                                                                     |
                                                                                     |  cluster {
                                                                                     |    seed-nodes = ["akka.tcp://PushStandaloneTest@127.0.0.1:2551"]
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
  val route = new PushCtrRoute4Odin(system, pushCtrResolver).routes
  val pushCtrRoute = Directives.respondWithHeader(RawHeader("Access-Control-Allow-Origin", "*"))(route)

  implicit val materializer = ActorMaterializer()
  val source = Http().bind(interface = "127.0.0.1", port = 8080)
  source.runForeach(_.handleWith(pushCtrRoute))

  val pushId = "1496468844125636434"
  val ts1 = new GregorianCalendar(2015, 11, 5, 10, 20, 12).getTime.getTime
  pushCtrResolver ! PushEvent(pushId, "some title", 10, 2, ts1, System.currentTimeMillis)

  Thread.sleep(1000 * 1000)
}
