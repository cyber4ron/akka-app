package com.wandoujia.statuscentre.inbound.consumer

import akka.actor.ActorSystem

object KafkaConsumer {
  def loadConfig(system: ActorSystem, configPath: String, f: String => AnyRef): Map[String, Any] = {
    import scala.collection.JavaConverters._
    system.settings.config.getConfig(configPath).root.unwrapped.asScala.toMap map {
      case (k, v) => k -> f(v.toString)
    }
  }
}

trait KafkaConsumer {

}
