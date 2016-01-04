package com.wandoujia.statuscentre.core.pushctr

import akka.actor.{ Terminated, Props, Actor }
import PushCtr.Join
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object PushCtrSupervisor {

  case object PushCtrCounter

  def props: Props = Props(classOf[PushCtrSupervisor])
}

class PushCtrSupervisor extends Actor {

  import PushCtrSupervisor._

  var pushCtrCnt = 0L

  private val log = LoggerFactory.getLogger(this.getClass)

  override def preStart() = {
    context.system.scheduler.schedule(0.seconds, 10.seconds, self, PushCtrCounter)(context.dispatcher)
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
      log.info("PushCtr actor num: {}", pushCtrCnt)
  }
}
