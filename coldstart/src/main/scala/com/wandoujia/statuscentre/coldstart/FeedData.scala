package com.wandoujia.statuscentre.coldstart

import scala.collection.immutable.HashMap
import scala.concurrent.Future
import scala.io.Source

object StringParam {
  def unapply(str: String): Option[String] = Some(str)
}

object FeedData extends App {

  var kv = HashMap.empty[String, String]

  def parse(args: List[String]): Unit = args match {
    case ("--targetQps" | "-t") :: StringParam(value) :: tail =>
      kv = kv.updated("targetQps", value)
      parse(tail)
    case ("--fileName" | "-f") :: StringParam(value) :: tail =>
      kv = kv.updated("targetQps", value)
      parse(tail)
    case Nil =>
    case _   => println("parse args error.")
  }

  parse(args.toList)

  val targetQps = kv.get("targetQps").get.toInt
  var currentQps: Int = _
  var requestCount = 0
  var (low, high) = (0, 1000) // ms

  def check() {

    Thread.sleep(1000)

    currentQps = requestCount

    if (math.abs(currentQps - targetQps) < 5) ()
    else if (low >= high && !(high <= 0 && currentQps < targetQps || low >= 1000 && currentQps > targetQps)) { low = 0; high = 1000 }
    else if (currentQps > targetQps) low = (low + high) / 2 + 1
    else high = (low + high) / 2

    requestCount = 0

    check()
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  Future { check() }

  var mockTime = 20
  Future {
    Thread.sleep(20 * 1000); mockTime = 1; println("====> " + mockTime)
    Thread.sleep(20 * 1000); mockTime = 15; println("====> " + mockTime)
    Thread.sleep(20 * 1000); mockTime = 7; println("====> " + mockTime)
  }

  val filename = kv.get("fileName").get
  val url = ""

  /**
   * line format:
   * pushId   pushTitle   shows   clicks  ts(min/hour/day)
   */
  for (line <- Source.fromFile(filename).getLines()) {
    val id, title, shows, clicks, ts = line.split("\\t")
    val s = url.format(id, title, shows, clicks, ts) // title问题，post

    // mock post
    Thread.sleep(mockTime)
    requestCount += 1

    // sleep
    Thread.sleep((low + high) >> 1)
  }
}
