package com.wandoujia.statuscentre.webapp

import org.slf4j.LoggerFactory
import spray.json._

object OdinResponseBuilder {
  private val log = LoggerFactory.getLogger(OdinResponseBuilder.getClass)

  def buildPushesInTimeRange(pushes: Seq[(String, String)], total: Int): String = {
    try {
      val obj = JsObject(
        "data" -> JsArray(pushes.map { push =>
          JsObject(
            "id" -> JsString(push._1),
            "title" -> JsString(push._2))
        }: _*),
        "totalItems" -> JsNumber(total),
        "statusInfo" -> JsObject(
          "text" -> JsString("ok"),
          "parameters" -> JsObject()))
      PrettyPrinter(obj)
    } catch {
      case ex: Throwable =>
        log.error(s"building pushes in time range failed, ex: ${ex.getMessage}")
        buildFailedInfo("building json failed.")
    }
  }

  def buildRecentTopPushes(sortedPushes: Seq[(String, String, Int, Int)], total: Int): String = {
    try {
      val obj = JsObject(
        "data" -> JsArray(sortedPushes.map { push =>
          JsObject(
            "id" -> JsString(push._1),
            "title" -> JsString(push._2),
            "shows" -> JsNumber(push._3),
            "clicks" -> JsNumber(push._4))
        }: _*),
        "totalItems" -> JsNumber(total),
        "statusInfo" -> JsObject(
          "text" -> JsString("ok"),
          "parameters" -> JsObject()))
      PrettyPrinter(obj)
    } catch {
      case ex: Throwable =>
        log.error(s"building recent top push ctr failed, ex: ${ex.getMessage}")
        buildFailedInfo("building json failed.")
    }
  }

  def buildPushCtrItem(id: String, title: String, shows: Int, clicks: Int, fromTs: Long, toTs: Long): String = {
    try {
      val obj = JsObject(
        "data" -> JsObject(
          "id" -> JsString(id),
          "title" -> JsString(title),
          "shows" -> JsNumber(shows),
          "clicks" -> JsNumber(clicks),
          "fromTs" -> JsNumber(fromTs),
          "toTs" -> JsNumber(toTs)),
        "totalItems" -> JsNumber(1),
        "statusInfo" -> JsObject(
          "text" -> JsString("ok"),
          "parameters" -> JsObject()))
      PrettyPrinter(obj)
    } catch {
      case ex: Throwable =>
        log.error(s"building push ctr item failed, ex: ${ex.getMessage}")
        buildFailedInfo("building json failed.")
    }
  }

  def buildPushCtrSerial(id: String, title: String, serial: Seq[(Long, Int, Int)]): String = {
    try {
      val obj = JsObject(
        "data" -> JsArray(serial.map { point =>
          JsObject(
            "id" -> JsString(id),
            "title" -> JsString(title),
            "ts" -> JsNumber(point._1),
            "shows" -> JsNumber(point._2),
            "clicks" -> JsNumber(point._3))
        }: _*),
        "totalItems" -> JsNumber(serial.length),
        "statusInfo" -> JsObject(
          "text" -> JsString("ok"),
          "parameters" -> JsObject()))
      PrettyPrinter(obj)
    } catch {
      case ex: Throwable =>
        log.error(s"building push ctr serial failed, ex: ${ex.getMessage}")
        buildFailedInfo("building json failed.")
    }
  }

  def buildFailedInfo(info: String): String = {
    s"""{
       |  "data": {},
       |  "totalItems": 0,
       |  "statusInfo": {
       |    "text": "$info",
       |    "parameters": {}
       |  }
       |}""".stripMargin
  }
}
