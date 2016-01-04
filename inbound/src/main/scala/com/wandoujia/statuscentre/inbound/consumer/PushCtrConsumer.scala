package com.wandoujia.statuscentre.inbound.consumer

import akka.actor._
import com.wandoujia.statuscentre.core.pushctr.Models.Event
import com.wandoujia.statuscentre.inbound.consumer.PushCtrConsumer.{ Consume, Recover, Stop }
import com.wandoujia.statuscentre.inbound.parser.Parser
import kafka.consumer.KafkaStream
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object PushCtrConsumer {
  def props[K, V](stream: KafkaStream[K, V], parserBindings: Map[String, Parser], resolver: ActorRef) =
    Props(classOf[PushCtrConsumer[K, V]], stream, parserBindings, resolver)

  case object Consume

  case object Stop

  case object Resend

  case object Recover
}

class PushCtrConsumer[K, V](stream: KafkaStream[K, V],
                            parserBindings: Map[String, Parser],
                            resolver: ActorRef) extends Actor with KafkaConsumer with Stash {

  import context._

  private val log = LoggerFactory.getLogger(this.getClass)

  private var waitingTimer: Option[Cancellable] = None

  override def preStart(): Unit = {
    super[Actor].preStart()
    context.system.eventStream.subscribe(self, classOf[DeadLetter])
    self ! Consume // 保险起见可用定时器。测试时多关掉几次shards试下
    become(normal)
  }

  override def postStop(): Unit = {
    super[Actor].postStop()
  }

  def normal: Receive = {
    case Consume =>
      try {
        val next = stream.iterator().next()
        if (parserBindings.contains(next.topic)) {
          val event = parserBindings(next.topic).parse(next.message())
          if (event.isDefined) {
            resolver ! event.get
          }
        }
      } finally {
        self ! Consume
      }

    case dl @ DeadLetter =>
      self ! dl
      become(abnormal)

    case Stop => self ! PoisonPill // 可能有未处理的dead letter. todo: graceful shutdown
  }

  def abnormal: Receive = {
    case DeadLetter(evt: Event, sender, recipient) => // 缺点是shards不可用时会不停的发
      resolver ! evt // resend
      waitingTimer match {
        case Some(timer) => timer.cancel()
      }
      waitingTimer = Some(context.system.scheduler.scheduleOnce(30.seconds, self, Recover))

    case Recover =>
      become(normal)

    case msg @ _ => self ! msg
  }

  override def receive: Receive = null
}
