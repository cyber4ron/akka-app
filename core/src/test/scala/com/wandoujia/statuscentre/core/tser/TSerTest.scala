package com.wandoujia.statuscentre.core.tser

import com.wandoujia.statuscentre.core.tser.TSer.Freq

object TSerTest extends App {
  val tSer = TSer(Freq.HOUR)

  tSer.acc(1449282012000L, 10) // 2015-12-05 10:20:12.0
  tSer.acc(1449282073000L, 10) // 2015-12-05 10:21:13.0
  tSer.acc(1449242012000L, 10) // 2015-12-04 23:13:32.0

  tSer.set(1449280801000L, 5) // 2015-12-05 10:00:01.0

  val from = 1449232012000L // 2015-12-04 20:26:52.0
  val to = 1449280800000L // 2015-12-05 10:00:00.0
  val to2 = 1449280801000L // 2015-12-05 10:00:01.0
  val s = tSer.range(from, to2)

  val sum = tSer.sum()
  println(sum)
  s.foreach(println)

  // round
  val ts = new java.util.GregorianCalendar(2015, 10, 25, 0, 0, 0).getTime.getTime
  val ts2 = new java.util.GregorianCalendar(2015, 10, 25, 12, 0, 0).getTime.getTime
  val ts3 = new java.util.GregorianCalendar(2015, 10, 25, 23, 59, 59).getTime.getTime
  val ts4 = new java.util.GregorianCalendar(2015, 5, 25, 23, 59, 59).getTime.getTime

  import TSer._

  println(new java.sql.Timestamp(round(ts, Freq.DAY)))
  println(new java.sql.Timestamp(round(ts2, Freq.DAY)))
  println(new java.sql.Timestamp(round(ts3, Freq.DAY)))

  println(new java.sql.Timestamp(round(ts3, Freq.WEEK)))

  println(new java.sql.Timestamp(round(ts3, Freq.MONTH)))
  println(new java.sql.Timestamp(round(ts4, Freq.MONTH)))

  println(new java.sql.Timestamp(round(ts3, Freq.HOUR)))
  println(new java.sql.Timestamp(round(ts3, Freq.MINUTE)))

  println(new java.sql.Timestamp(round(ts3, Freq.IN_RECORD))) // 08:30
  // new java.sql.Timestamp(0L).getTimezoneOffset // -510 = 8.5 hour
  // new java.sql.Timestamp(548860578808L).getTimezoneOffset // 1987-05-24 / -540 = 9 hour
}
