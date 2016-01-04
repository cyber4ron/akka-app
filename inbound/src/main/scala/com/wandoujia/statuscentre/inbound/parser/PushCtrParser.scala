package com.wandoujia.statuscentre.inbound.parser

import java.util.Base64

import com.wandoujia.logv3.model.packages.LogEventPackage.TaskEvent
import com.wandoujia.logv3.model.packages.LogMuce
import com.wandoujia.statuscentre.core.pushctr.PushCtr.PushEvent
import org.slf4j.LoggerFactory

import scala.util.parsing.json.JSON

object PushCtrParser {
  val topic = "muce.event.client.TaskEvent"
}

class PushCtrParser extends Parser {
  private val log = LoggerFactory.getLogger(this.getClass)

  override val topic = PushCtrParser.topic

  override def parse(line: Any): Option[PushEvent] = {
    try {
      val event = LogMuce.LogReportEvent.parseFrom(line.asInstanceOf[Array[Byte]])
      event.getEventPackage.getTaskEvent match {
        case taskEvent: TaskEvent =>
          val action = taskEvent.getAction.toString
          if (!taskEvent.getAction.toString.equals("PUSH")) return None

          log.debug(s"base64: ${Base64.getEncoder.encodeToString(line.asInstanceOf[Array[Byte]])}")
          log.debug(s"action: $action")

          var showed, clicked = 0
          val status = taskEvent.getStatus.toString
          val result = taskEvent.getResult.toString
          log.debug(s"status: $status, result: $result")

          if (status.equals("READY") && !result.equals("CANCEL")) showed = 1
          if (status.equals("TRIGGER")) clicked = 1

          if (showed == 0 && clicked == 0) return None

          val resultInfo = taskEvent.getResultInfo // json to get push2_id
          val resultJson = JSON.parseFull(resultInfo)
          log.debug(s"resultInfo: $resultInfo")

          if (resultJson.isEmpty) {
            log.debug("parse line error: resultJson.isEmpty") // pengfei说是其他业务打的，正常
            return None
          }
          if (!resultJson.get.asInstanceOf[Map[String, String]].contains("push2_id")) {
            log.warn("parse line error: does not contain push2_id")
            return None
          }

          val pushId = resultJson.get.asInstanceOf[Map[String, String]].get("push2_id").get
          val pushTitle = resultJson.get.asInstanceOf[Map[String, String]].get("push2_title").get
          val serverTime = event.getCommonPackage.getTimePackage.getServerTimestamp

          log.debug("event: " + Some(PushEvent(pushId, pushTitle, showed, clicked, serverTime, serverTime).toString))
          Some(PushEvent(pushId, pushTitle, showed, clicked, serverTime, serverTime)) // client time ; server time

        case _ => None
      }
    } catch {
      case e: Exception =>
        log.error("parse line error", e)
        None
    }
  }
}
