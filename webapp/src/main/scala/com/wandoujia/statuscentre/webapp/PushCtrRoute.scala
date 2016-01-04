package com.wandoujia.statuscentre.webapp

import akka.actor.{ ActorRef, ActorSelection, ActorSystem }
import akka.http.scaladsl.server._
import akka.pattern.ask
import akka.util.Timeout
import com.googlecode.protobuf.format.JsonFormat
import com.wandoujia.statuscentre.core.pushctr.MetricAggregator.{ GetAllPath, GetPushesByTime, GetRecentTopPushes }
import com.wandoujia.statuscentre.core.pushctr.PushCtr
import com.wandoujia.statuscentre.core.pushctr.PushCtr._
import com.wandoujia.statuscentre.core.tser.TSer
import com.wandoujia.statuscentre.proto.PushCtrProtos
import com.wandoujia.statuscentre.proto.PushCtrProtos.PushCtrUntilNow.MetricInfo
import com.wandoujia.statuscentre.proto.PushCtrProtos._
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

class PushCtrRoute(val system: ActorSystem, resolver: ActorRef, aggregator: ActorSelection) extends Directives {
  private val log = LoggerFactory.getLogger(classOf[PushCtrRoute])
  import TSer.Freq.getFreq
  private implicit val askTimeout: Timeout = 20.seconds

  val route: Route = {
    path("ping") {
      get {
        log.info("pong!")
        complete("pong!")
      }
    } ~ pathPrefix("pushCtr") {
      path("getCtr") {
        get {
          parameter('pushId.as[String])((pushId: String) =>
            complete({
              Await.result(resolver ? CtrUntilNow(pushId), askTimeout.duration) match {
                case CtrResult(title, shows, showFromTs, showToTs, clicks, clickFromTs, clickToTs) =>
                  JsonFormat.printToString(PushCtrUntilNow.newBuilder()
                    .setId(pushId)
                    .setTitle(title)
                    .setShow(MetricInfo.newBuilder()
                      .setMetric(shows)
                      .setFrom(showFromTs.toString)
                      .setTo(showToTs.toString)
                      .build())
                    .setClick(MetricInfo.newBuilder().setMetric(clicks)
                      .setFrom(clickFromTs.toString)
                      .setTo(clickToTs.toString)
                      .build())
                    .build())
                case err: PushCtr.Error => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                  .setId(pushId)
                  .setError(err.reason.getOrElse("null"))
                  .build())
                case _ => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                  .setId(pushId)
                  .setError("undefined.")
                  .build())
              }
            }))
        }
      }
    } ~ pathPrefix("pushCtr") {
      path("getCtrRangeSum") {
        get {
          parameter('pushId.as[String], 'fromTs.as[String], 'toTs.as[String])((pushId: String, fromTs: String, toTs: String) =>
            complete({
              Await.result(resolver ? CtrRangeSum(pushId, fromTs.toLong, toTs.toLong), askTimeout.duration) match {
                case CtrRangeSumResult(id, title, shows, clicks) =>
                  JsonFormat.printToString(PushCtrRangeSum.newBuilder()
                    .setId(pushId)
                    .setTitle(title)
                    .setShows(shows)
                    .setClicks(clicks)
                    .build())
                case err: PushCtr.Error => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                  .setId(pushId)
                  .setError(err.reason.getOrElse("null"))
                  .build())
                case info: PushCtr.Info => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                  .setId(pushId)
                  .setError(info.info.getOrElse(""))
                  .build())
                case _ => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                  .setId(pushId)
                  .setError("undefined.")
                  .build())
              }
            }))
        }
      }
    } ~ pathPrefix("pushCtr") {
      path("getCtrRange") {
        get {
          parameter('pushId.as[String], 'fromTs.as[String], 'toTs.as[String], 'freq.as[String])((pushId: String, fromTs: String, toTs: String, frequency: String) =>
            complete({
              val freq = getFreq(frequency)
              if (freq.isEmpty) JsonFormat.printToString(PushCtrProtos.Error.newBuilder().setId(pushId).setError("parameter error.").build())
              else {
                Await.result(resolver ? CtrRange(pushId, fromTs.toLong, toTs.toLong, freq.get), askTimeout.duration) match {
                  case CtrRangeResult(title, series) => JsonFormat.printToString(PushCtrRange.newBuilder()
                    .setId(pushId)
                    .setTitle(title)
                    .setTimeline(Series.newBuilder()
                      .addAllSeries(series.map(x => DataPoint.newBuilder().setTs(x._1.toString).setShows(x._2).setClicks(x._3).build()))
                      .setFreq(frequency)
                      .build())
                    .build())
                  case err: PushCtr.Error => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                    .setId(pushId)
                    .setError(err.reason.getOrElse("null"))
                    .build())
                  case _ => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                    .setId(pushId)
                    .setError("undefined.")
                    .build())
                }
              }
            }))
        }
      }
    } ~ pathPrefix("pushCtr") {
      path("getPush") {
        get {
          parameter('pushId.as[String], 'freq.as[String] ? "day")((pushId: String, frequency: String) =>
            complete({
              val freq = getFreq(frequency)
              if (freq.isEmpty) JsonFormat.printToString(PushCtrProtos.Error.newBuilder().setId(pushId).setError("parameter error.").build())
              else {
                Await.result(resolver ? GetPush(pushId, freq), askTimeout.duration) match {
                  case PushCtr.PushInfo(id, title, series, _) =>
                    JsonFormat.printToString(PushCtrProtos.PushInfo.newBuilder()
                      .setTitle(title)
                      .setId(pushId)
                      .setTimeline(Series.newBuilder()
                        .addAllSeries(series.get.map(x => DataPoint.newBuilder().setTs(x._1.toString).setShows(x._2).setClicks(x._3).build()))
                        .setFreq(frequency)
                        .build())
                      .build())
                  case err: PushCtr.Error => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                    .setId(pushId)
                    .setError(err.reason.getOrElse("null"))
                    .build())
                  case _ => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                    .setId(pushId)
                    .setError("undefined.")
                    .build())
                }
              }
            }))
        }
      }
    } ~ pathPrefix("pushCtr") {
      path("getPushesByTime") {
        get {
          parameter('fromTs.as[String] ? "0", 'toTs.as[String] ? Long.MaxValue.toString)((fromTs: String, toTs: String) =>
            complete({
              Await.result(aggregator ? GetPushesByTime(fromTs.toLong, toTs.toLong, 1, Int.MaxValue), askTimeout.duration) match {
                case (pushes: Seq[PushCtr.PushInfo], total: Int) => JsonFormat.printToString(PushesInfo.newBuilder()
                  .addAllPushes(pushes.toSeq.map(x => PushCtrProtos.PushInfo.newBuilder()
                    .setId(x.pushId).setTitle(x.pushTitle)
                    .build()))
                  .build())
                case _ => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                  .setError("undefined.")
                  .build())
              }
            }))
        }
      }
    } ~ pathPrefix("pushCtr") {
      path("getRecentTopPushes") {
        get {
          parameter('toTs.as[String] ? System.currentTimeMillis.toString, 'range.as[String] ? "day")((toTs: String, frequency: String) =>
            complete({
              val freq = getFreq(frequency)

              if (freq.isEmpty) JsonFormat.printToString(PushCtrProtos.Error.newBuilder().setError("parameter error.").build())
              else {
                Await.result(aggregator ? GetRecentTopPushes(toTs.toLong, freq.get, 1, Int.MaxValue), askTimeout.duration) match {
                  case (pushes: Seq[PushCtr.CtrRangeSumResult], total: Int) => JsonFormat.printToString(SortedPushes.newBuilder()
                    .addAllSortedPushes(pushes.toSeq.map(x => PushCtrProtos.SortedPushes.Push.newBuilder()
                      .setId(x.id).setTitle(x.pushTitle).setShows(x.shows).setClicks(x.clicks)
                      .build()))
                    .build())
                  case _ => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                    .setError("undefined.")
                    .build())
                }
              }
            }))
        }
      }
    } ~ pathPrefix("pushCtr") {
      path("allPushPath") {
        get {
          complete {
            Await.result(aggregator ? GetAllPath, askTimeout.duration) match {
              case pushes: Array[Any] => s"{${pushes.mkString(",")}}"
              case _ => JsonFormat.printToString(PushCtrProtos.Error.newBuilder()
                .setError("undefined.")
                .build())
            }
          }
        }
      }
    }
  }

  val commands = {
    // todo: graceful shutdown
  }

  // todo: feed等内部接口
  val routes = route
}
