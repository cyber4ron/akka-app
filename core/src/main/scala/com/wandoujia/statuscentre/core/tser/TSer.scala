package com.wandoujia.statuscentre.core.tser

import java.util.GregorianCalendar

import TSer.FreqTimeSpan
import org.joda.time.{ DateTimeZone, DateTime }
import org.slf4j.LoggerFactory

import scala.collection.immutable.TreeMap

object TSer {
  val log = LoggerFactory.getLogger(TSer.getClass)

  @SerialVersionUID(1L)
  object Freq extends Enumeration {
    type Freq = Value
    val SECOND, MINUTE, HOUR, DAY, WEEK, MONTH, IN_RECORD = Value

    def getFreq(freq: String): Option[Freq.Freq] = freq match {
      case "sec"    => Some(SECOND)
      case "min"    => Some(MINUTE)
      case "hour"   => Some(HOUR)
      case "day"    => Some(DAY)
      case "week"   => Some(WEEK)
      case "month"  => Some(MONTH)
      case "in_rec" => Some(IN_RECORD)
      case _        => None
    }
  }

  import Freq._

  object FreqTimeSpan extends Enumeration {
    type FreqTimeSpan = Value

    val SECOND_SPAN = 1000L
    val MINUTE_SPAN = SECOND_SPAN * 60
    val HOUR_SPAN = MINUTE_SPAN * 60
    val DAY_SPAN = HOUR_SPAN * 24
    val WEEK_SPAN = DAY_SPAN * 7
    val MONTH_SPAN = DAY_SPAN * 30
    val IN_RECORD_SPAN = MONTH_SPAN * 12 * 10

    def getTimeSpan(freq: Freq) = freq match {
      case SECOND    => SECOND_SPAN
      case MINUTE    => MINUTE_SPAN
      case HOUR      => HOUR_SPAN
      case DAY       => DAY_SPAN
      case WEEK      => WEEK_SPAN
      case MONTH     => MONTH_SPAN
      case IN_RECORD => IN_RECORD_SPAN
    }
  }

  val timeZoneOffset = 8 * 3600 * 1000L // UTC timezone: China Standard Time
  val weekTsOffset = 4 * FreqTimeSpan.DAY_SPAN // 1970-1-1 is thursday

  val minTs = new GregorianCalendar(2000, 1, 1, 0, 0, 0).getTime.getTime
  val maxTs = new GregorianCalendar(3000, 1, 1, 0, 0, 0).getTime.getTime

  def round(ts: Long, freq: Freq.Value) = freq match {
    case DAY  => ts - (ts + timeZoneOffset) % FreqTimeSpan.getTimeSpan(freq)
    case WEEK => ts - (ts + timeZoneOffset) % FreqTimeSpan.getTimeSpan(freq) - weekTsOffset
    case MONTH =>
      val date = new DateTime(ts, DateTimeZone.forID("Asia/Shanghai"))
      val year = date.year().getAsString.toInt
      val month = date.monthOfYear().getAsString.toInt
      new DateTime(year, month, 1, 0, 0, 0, 0).getMillis
    case IN_RECORD => 0L
    case _         => ts - ts % FreqTimeSpan.getTimeSpan(freq)
  }

  def zip(tSer1: TreeMap[Long, Int], tSer2: TreeMap[Long, Int]): Seq[(Long, Int, Int)] =
    (for ((ts, _) <- tSer1 ++ tSer2) yield (ts, tSer1.getOrElse(ts, 0), tSer2.getOrElse(ts, 0))).toSeq

  def zip(tSer1: TSer, tSer2: TSer): Option[Seq[(Long, Int, Int)]] =
    if (tSer1.freq != tSer1.freq) None
    else Some(TSer.zip(tSer1.tSer, tSer2.tSer).toSeq)

  def apply(freq: Freq): TSer = new TSer(freq)
}

import TSer.Freq._

/**
 * thread not safe(lost update), 只用于actor串行化的更新
 */
@SerialVersionUID(1L)
class TSer(val freq: Freq) extends Serializable {
  import TSer.log

  var tSer = TreeMap.empty[Long, Int]

  def set(ts: Long, value: Int) {
    if (ts < TSer.minTs || ts > TSer.maxTs) {
      log.warn(s"$ts is invalid.")
      return
    }

    tSer = tSer.updated(TSer.round(ts, freq), value)
  }

  def acc(ts: Long, value: Int) {
    if (ts < TSer.minTs || ts > TSer.maxTs) {
      log.warn(s"$ts is invalid.")
      return
    }

    val tsR = TSer.round(ts, freq)
    if (!tSer.contains(tsR))
      set(ts, value)
    else tSer = tSer.updated(tsR, tSer.get(tsR).get + value)
  }

  def merge(other: TSer) {
    other.tSer.foreach(kv => acc(kv._1, kv._2))
  }

  def fromTs = if (tSer.isEmpty) -1L else tSer.firstKey

  def toTs = if (tSer.isEmpty) -1L else tSer.lastKey

  /**
   * [, )
   */
  def range(fromTs: Long, toTs: Long) = {
    tSer.range(fromTs, toTs)
  }

  /**
   * equals: range(fromTs - FreqTimeSpan.getTimeSpan(freq) + 1, toTs)
   */
  def overlap(fromTs: Long, toTs: Long): Boolean = {
    if (tSer.isEmpty) false
    else toTs > this.fromTs && fromTs < this.toTs + FreqTimeSpan.getTimeSpan(freq)
  }

  def sum() = tSer.values.sum

  def sumRange(fromTs: Long, toTs: Long) = {
    tSer.range(fromTs, toTs).values.sum
  }

  def clear() {
    tSer = TreeMap.empty[Long, Int]
  }

  def clearRange(fromTs: Long, toTs: Long) {
    for (ts <- tSer.range(fromTs, toTs).keys) tSer = tSer - ts
  }
}
