package com.wandoujia.statuscentre.inbound.consumer

import java.util.Properties

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import PushCtrConsumerSupervisor.{ Join, PushCtrCounter }
import com.wandoujia.statuscentre.inbound.parser.{ PushCtrParser, Parser }
import kafka.consumer.{ Consumer, ConsumerConfig, Whitelist }
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.concurrent.duration._

object PushCtrConsumerSupervisor {

  case class Join()
  case class PushCtrCounter()

  def props(resolver: ActorRef): Props = Props(classOf[PushCtrConsumerSupervisor], resolver)
}

class PushCtrConsumerSupervisor(resolver: ActorRef) extends Actor {
  val log = LoggerFactory.getLogger(this.getClass)

  import context.dispatcher

  val streamNum = context.system.settings.config.getInt("pushctr.inbound.consumer.stream.num")
  log.info(s"streamNum: $streamNum")

  val properties = new Properties()
  context.system.settings.config.getConfig("kafka").entrySet().foreach { entry =>
    properties.put(entry.getKey, entry.getValue.unwrapped.toString)
  }

  val consumerConfig: ConsumerConfig = new ConsumerConfig(properties)
  val consumerConnector = Consumer.create(consumerConfig)
  val filter = new Whitelist(PushCtrParser.topic)

  val parserBindings = KafkaConsumer.loadConfig(context.system,
    "status-centre.inbound.parser-bindings",
    (classPath: String) => Class.forName(classPath).newInstance().asInstanceOf[AnyRef])

  val consumerActors = consumerConnector.createMessageStreamsByFilter(filter, streamNum).map(stream =>
    context.actorOf(PushCtrConsumer.props(stream, parserBindings.asInstanceOf[Map[String, Parser]], resolver)))

  var pushCtrCnt = 0L

  override def supervisorStrategy = OneForOneStrategy() {
    case e: Exception => log.error(s"ConsumerActor thrown an exception: $e, restarting..."); Restart
  }

  override def preStart() = {
    context.system.scheduler.schedule(0.seconds, 10.seconds, self, PushCtrCounter)
  }

  override def receive: Receive = {
    case Join =>
      log.info(s"PushCtr Supervisor Join: ${sender().path}")
      pushCtrCnt += 1
      context watch sender()

    case Terminated(ref) =>
      log.info(s"Terminated: ${sender().path}")
      pushCtrCnt -= 1
      context unwatch ref

    case PushCtrCounter =>
      log.info(s"PushCtr actor num: $pushCtrCnt")
  }
}
