package com.wandoujia.statuscentre.webapp

object JsonTest extends App {
  val xx = OdinResponseBuilder.buildFailedInfo("x")
  println(xx)

  val xxx = OdinResponseBuilder.buildPushCtrItem("id", "title", 10, 1, 100, 200)
  println(xxx)

  val aa = OdinResponseBuilder.buildPushCtrSerial("id", "title", Array((200L, 10, 1), (300L, 11, 2)))
  print(aa)

  val cc = OdinResponseBuilder.buildPushesInTimeRange(Array(("id1", "title1"), ("id2", "title2")), 100)
  print(cc)

  val ee = OdinResponseBuilder.buildRecentTopPushes(Seq(("id1", "title1", 100, 10), ("id2", "title2", 110, 11)), 100)
  print(ee)

  Thread.sleep(200L)
}
