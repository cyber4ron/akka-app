package com.wandoujia.statuscentre.webapp

import java.util.GregorianCalendar

import akka.actor.{ ActorRef, ActorSelection, ActorSystem }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.pattern.ask
import akka.util.Timeout
import com.wandoujia.statuscentre.core.pushctr.MetricAggregator.{ GetPushesByTime, GetRecentTopPushes }
import com.wandoujia.statuscentre.core.pushctr.PushCtr
import com.wandoujia.statuscentre.core.pushctr.PushCtr._
import com.wandoujia.statuscentre.core.tser.TSer
import com.wandoujia.statuscentre.core.tser.TSer.FreqTimeSpan
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._

class PushCtrRoute4Odin(val system: ActorSystem, resolver: ActorRef, aggregator: ActorSelection) extends Directives {
  private val log = LoggerFactory.getLogger(classOf[PushCtrRoute4Odin])
  import TSer.Freq.getFreq
  private implicit val askTimeout: Timeout = 20.seconds

  val minTs = new GregorianCalendar(2015, 1, 1, 0, 0, 0).getTime.getTime
  val maxTs = new GregorianCalendar(2115, 1, 1, 0, 0, 0).getTime.getTime

  val route: Route = {
    path("ping") {
      get {
        log.info("pong!")
        complete("pong!")
      }
    } ~ pathPrefix("api") {
      path("getCtr") {
        get {
          parameter('pushId.as[String]) { pushId =>
            complete {
              Await.result(resolver ? CtrUntilNow(pushId), askTimeout.duration) match {
                case CtrResult(title, shows, showFromTs, showToTs, clicks, clickFromTs, clickToTs) =>
                  val fromTs = (showFromTs, clickFromTs) match {
                    case (-1L, -1L) => -1
                    case (-1L, x)   => x
                    case (x, -1L)   => x
                    case (x, y)     => math.min(x, y)
                  }
                  OdinResponseBuilder.buildPushCtrItem(pushId, title, shows, clicks, fromTs, math.max(showToTs, clickToTs))
                case err: PushCtr.Error =>
                  log.error(s"getCtr failed, reason: id: ${err.id}, reason: ${err.reason}")
                  OdinResponseBuilder.buildFailedInfo(err.reason.getOrElse("undefined."))
                case undefined =>
                  log.error(s"getCtr failed, reason: $undefined")
                  OdinResponseBuilder.buildFailedInfo("undefined.")
              }
            }
          }
        }
      } ~ path("getCtrRange") {
        get {
          parameter('pushId.as[String], 'fromTs.as[String], 'toTs.as[String], 'freq.as[String]) { (pushId, fromTs, toTs, frequency) =>
            complete {
              val freq = getFreq(frequency)
              if (freq.isEmpty) OdinResponseBuilder.buildFailedInfo("parameter error.")
              else {
                Await.result(resolver ? CtrRange(pushId, fromTs.toLong, toTs.toLong, freq.get), askTimeout.duration) match {
                  case CtrRangeResult(title, series) =>
                    OdinResponseBuilder.buildPushCtrSerial(pushId, title, series)
                  case err: PushCtr.Error =>
                    log.error(s"getCtr failed, id: ${err.id}, reason: ${err.reason}")
                    OdinResponseBuilder.buildFailedInfo(err.reason.getOrElse("undefined."))
                  case undefined =>
                    log.error(s"getCtr failed, reason: $undefined")
                    OdinResponseBuilder.buildFailedInfo("undefined.")
                }
              }
            }
          }
        }
      } ~ path("getCtrRangeFull") {
        get {
          parameter('pushId.as[String], 'fromTs.as[String], 'toTs.as[String], 'freq.as[String]) { (pushId, fromTs, toTs, frequency) =>
            complete {
              val freq = getFreq(frequency)
              if (freq.isEmpty || fromTs >= toTs) OdinResponseBuilder.buildFailedInfo("parameter error.")
              else {
                Await.result(resolver ? CtrRange(pushId, fromTs.toLong, toTs.toLong, freq.get), askTimeout.duration) match {
                  case CtrRangeResult(title, series) =>
                    val seriesFull = new mutable.ArrayBuffer[(Long, Int, Int)]
                    val step = FreqTimeSpan.getTimeSpan(freq.get)
                    var curTs = math.ceil(math.max(fromTs.toLong, minTs).toDouble / step).toLong * step
                    val _toTs = math.ceil(math.min(toTs.toLong, maxTs).toDouble / step).toLong * step
                    var idx = 0
                    while (curTs < _toTs && idx < series.size) {
                      val (ts, _, _) = series(idx)
                      curTs - ts match {
                        case x if x == 0 =>
                          seriesFull += series(idx); curTs += step; idx += 1
                        case x if x > 0 =>
                          seriesFull += series(idx); idx += 1
                        case x if x < 0 => seriesFull += ((curTs, 0, 0)); curTs += step
                      }
                    }
                    while (idx < series.size) {
                      seriesFull += series(idx)
                      idx += 1
                    }
                    while (curTs < _toTs) {
                      seriesFull += ((curTs, 0, 0))
                      curTs += step
                    }
                    OdinResponseBuilder.buildPushCtrSerial(pushId, title, seriesFull.toArray[(Long, Int, Int)])
                  case err: PushCtr.Error =>
                    log.error(s"getCtr failed, id: ${err.id}, reason: ${err.reason}")
                    OdinResponseBuilder.buildFailedInfo(err.reason.getOrElse("undefined."))
                  case undefined =>
                    log.error(s"getCtr failed, reason: $undefined")
                    OdinResponseBuilder.buildFailedInfo("undefined.")
                }
              }
            }
          }
        }
      } ~ path("getPushesByTime") {
        get {
          parameter('fromTs.as[String] ? "0", 'toTs.as[String] ? Long.MaxValue.toString, 'page.as[Int] ? 1, 'pageSize.as[Int] ? 20) { (fromTs, toTs, page, pageSize) =>
            complete {
              Await.result(aggregator ? GetPushesByTime(fromTs.toLong, toTs.toLong, page, pageSize), askTimeout.duration) match {
                case (pushes: Seq[PushCtr.PushInfo], total: Int) =>
                  OdinResponseBuilder.buildPushesInTimeRange(pushes.map(x => (x.pushId, x.pushTitle)), total)
                case err: PushCtr.Error =>
                  log.error(s"getCtr failed, id: ${err.id}, reason: ${err.reason}")
                  OdinResponseBuilder.buildFailedInfo(err.reason.getOrElse("undefined."))
                case undefined =>
                  log.error(s"getCtr failed, reason: $undefined")
                  OdinResponseBuilder.buildFailedInfo("undefined.")
              }
            }
          }
        }
      } ~ path("getRecentTopPushes") {
        get {
          parameter('toTs.as[String] ? System.currentTimeMillis.toString, 'range.as[String] ? "day", 'page.as[Int] ? 1, 'pageSize.as[Int] ? 20) { (toTs, frequency, page, pageSize) =>
            complete {
              val freq = getFreq(frequency)
              if (freq.isEmpty) OdinResponseBuilder.buildFailedInfo("parameter error.")
              else {
                Await.result(aggregator ? GetRecentTopPushes(toTs.toLong, freq.get, page, pageSize), askTimeout.duration) match {
                  case (pushes: Seq[PushCtr.CtrRangeSumResult], total: Int) =>
                    OdinResponseBuilder.buildRecentTopPushes(pushes.map(x => (x.id, x.pushTitle, x.shows, x.clicks)), total)
                  case err: PushCtr.Error =>
                    log.error(s"getCtr failed, id: ${err.id}, reason: ${err.reason}")
                    OdinResponseBuilder.buildFailedInfo(err.reason.getOrElse("undefined."))
                  case undefined =>
                    log.error(s"getCtr failed, reason: $undefined")
                    OdinResponseBuilder.buildFailedInfo("undefined.")
                }
              }
            }
          }
        }
      }
    }
  }
}
