package com.wandoujia.statuscentre

import akka.actor.{ ActorRef, ActorSystem }
import akka.contrib.pattern.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.wandoujia.statuscentre.core.pushctr.{ MetricAggregator, PushCtr, PushCtrSupervisor }
import com.wandoujia.statuscentre.webapp.{ PushCtrRoute, PushCtrRoute4Odin }
import org.slf4j.LoggerFactory

object PushStandaloneServer extends scala.App {
  class ServiceRoute(val system: ActorSystem, resolver: ActorRef) extends Directives {
    MetricAggregator.startAggregator(system, resolver)
    MetricAggregator.startAggregatorProxy(system)
    val aggregator = MetricAggregator.proxy(system)

    val route = new PushCtrRoute4Odin(system, resolver, aggregator).route ~
      new PushCtrRoute(system, resolver, aggregator).route
  }

  val log = LoggerFactory.getLogger(PushStandaloneServer.getClass)

  val env = if (System.getenv().containsKey("env")) System.getenv("env") else "dev"
  log.info("Run under " + env)

  val commonConfig = {
    val originalConfig = ConfigFactory.load()
    originalConfig.getConfig(env).withFallback(originalConfig)
  }
  implicit val system = ActorSystem("PushCtrStandalone", commonConfig)
  val pushCtrSuperVisor = system.actorOf(PushCtrSupervisor.props)

  ClusterSharding(system).start(PushCtr.shardName, Some(PushCtr.props(pushCtrSuperVisor)), PushCtr.idExtractor, PushCtr.shardResolver)
  val pushCtrResolver = ClusterSharding(system).shardRegion(PushCtr.shardName)
  val route = new ServiceRoute(system, pushCtrResolver).route
  val pushCtrRoute = Directives.respondWithHeader(RawHeader("Access-Control-Allow-Origin", "*"))(route)

  implicit val materializer = ActorMaterializer()
  val webConfig = system.settings.config.getConfig("web")
  val source = Http().bind(interface = webConfig.getString("hostname"), port = webConfig.getInt("port"))
  source.runForeach(_.handleWith(pushCtrRoute))
}

