package com.wandoujia.statuscentre.inbound

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import com.typesafe.config.ConfigFactory
import com.wandoujia.statuscentre.core.pushctr.PushCtr
import com.wandoujia.statuscentre.inbound.consumer.PushCtrConsumerSupervisor

object PushCtrInbound extends App {
  val env = if (System.getenv().containsKey("env")) System.getenv("env") else "dev"
  println("Run under " + env)

  val commonConfig = {
    val originalConfig = ConfigFactory.load()
    originalConfig.getConfig(env).withFallback(originalConfig)
  }
  val system = ActorSystem("PushCtrStandalone", commonConfig)

  ClusterSharding(system).start(PushCtr.shardName, None, PushCtr.idExtractor, PushCtr.shardResolver)
  val resolver = ClusterSharding(system).shardRegion(PushCtr.shardName)

  val consumerSupervisor = system.actorOf(PushCtrConsumerSupervisor.props(resolver))

  println("Starting PushCtr Inbound...")
}
