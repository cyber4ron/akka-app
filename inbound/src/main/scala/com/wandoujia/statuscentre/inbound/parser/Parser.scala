package com.wandoujia.statuscentre.inbound.parser

import com.wandoujia.statuscentre.core.pushctr.Models
import Models.Event
import Models._
import com.wandoujia.statuscentre.core.pushctr.Models
import com.wandoujia.statuscentre.core.pushctr.Models.Event

object Parser {

}

trait Parser {
  val topic: String

  def parse(line: Any): Option[Event]
}
