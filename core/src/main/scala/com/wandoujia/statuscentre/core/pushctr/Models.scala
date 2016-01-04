package com.wandoujia.statuscentre.core.pushctr

object Models {

  trait Command extends scala.Serializable { // case-to-case inheritance is prohibited
    val id: String
  }

  trait Event extends scala.Serializable {
    val id: String
  }
}
