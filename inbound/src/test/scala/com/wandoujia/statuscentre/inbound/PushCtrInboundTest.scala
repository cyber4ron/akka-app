package com.wandoujia.statuscentre.inbound

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterSharding
import akka.testkit.TestKit
import com.typesafe.config.{ ConfigFactory, ConfigValueFactory }
import com.wandoujia.logv3.model.packages.LogCommonPackage.{ ClientPackage, IdPackage, TimePackage }
import com.wandoujia.logv3.model.packages.LogEventPackage.TaskEvent.{ Action, Result, Status }
import com.wandoujia.logv3.model.packages.{ LogCommonPackage, LogEventPackage, LogMuce }
import com.wandoujia.statuscentre.core.pushctr.{ PushCtrSupervisor, PushCtr }
import com.wandoujia.statuscentre.inbound.consumer.PushCtrConsumerSupervisor
import com.wandoujia.statuscentre.inbound.parser.PushCtrParser
import kafka.admin.AdminUtils
import kafka.producer.{ KeyedMessage, Producer, ProducerConfig }
import kafka.server.{ KafkaConfig, KafkaServer }
import kafka.utils.{ MockTime, TestUtils, TestZKUtils, ZKStringSerializer }
import kafka.zk.EmbeddedZookeeper
import org.I0Itec.zkclient.ZkClient
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

class PushCtrInboundTest extends WordSpecLike with Matchers with BeforeAndAfterAll {

  var producer: Producer[String, Array[Byte]] = _
  var kafkaServer: KafkaServer = _
  var zkServer: EmbeddedZookeeper = _
  var zkClient: ZkClient = _
  var system: ActorSystem = _
  val partitionNum = 4

  val conf = """status-centre.inbound {
                 |  parser-bindings {
                 |    "TaskEvent" = "com.wandoujia.statuscentre.inbound.parser.PushCtrParser"
                 |  }
                 |}
                 |
                 |pushctr.inbound.consumer.stream.num = 4
                 |
                 |akka {
                 |  loglevel = INFO
                 |  stdout-loglevel = INFO
                 |  loggers = ["akka.event.slf4j.Slf4jLogger"]
                 |  event-handlers = ["akka.event.slf4j.Slf4jEventHandler"]
                 |
                 |  log-config-on-start = off
                 |
                 |  actor {
                 |    provider = "akka.cluster.ClusterActorRefProvider"
                 |  }
                 |}
                 |
                 |akka.remote.netty.tcp.hostname="127.0.0.1"
                 |akka.remote.netty.tcp.port = 2551
                 |akka.cluster.roles = ["entity"]
                 |
                 |akka.cluster.seed-nodes = [
                 |  "akka.tcp://PushCtrInboundTest@127.0.0.1:2551"
                 |]
                 |
                 |kafka {
                 |  zookeeper.connect = "127.0.0.1:2181"
                 |  consumer.timeout.ms = -1
                 |  auto.offset.reset = "smallest" # 这个必须有
                 |  consumer.id = "push_ctr_consumers"
                 |  zookeeper.sync.time.ms = 200
                 |  zookeeper.session.timeout.ms = 6000
                 |  num.consumer.fetchers = 2
                 |  auto.commit.interval.ms = 1000
                 |  group.id = "status_centre_push_ctr_0"
                 |}
                 |
                 |""".stripMargin

  override def beforeAll() {
    // setup Zookeeper
    val zkConnect = TestZKUtils.zookeeperConnect
    zkServer = new EmbeddedZookeeper(zkConnect)
    zkClient = new ZkClient(zkServer.connectString, 30000, 30000, ZKStringSerializer)

    // kafka broker
    val port = TestUtils.choosePort()
    val props = TestUtils.createBrokerConfig(0, port)
    val config = new KafkaConfig(props)
    kafkaServer = TestUtils.createServer(config, new MockTime())

    // create topic
    AdminUtils.createTopic(zkClient, PushCtrParser.topic, partitionNum, 1)

    val servers = List(kafkaServer)
    TestUtils.waitUntilMetadataIsPropagated(servers, PushCtrParser.topic, 0, 500)
    TestUtils.waitUntilLeaderIsElectedOrChanged(zkClient, PushCtrParser.topic, 0, 500)

    // setup producer
    val producerProps = TestUtils.getProducerConfig("127.0.0.1:" + port)
    val producerConfig = new ProducerConfig(producerProps)
    producer = new Producer(producerConfig)

    // overwrite zk.connect
    system = ActorSystem("PushCtrInboundTest", ConfigFactory.parseString(conf)
      .withValue("kafka.zookeeper.connect", ConfigValueFactory.fromAnyRef(zkServer.connectString)))

    // val msg = "CJkqEAAaBTEuMS4xIvYBClQKKGZlOGFlNDRkYTMwNTQzZGZhMWZlNmZmOGNmMTQ5OTdlMTJhMzMxYTISKGZlOGFlNDRkYTMwNTQzZGZhMWZlNmZmOGNmMTQ5OTdlMTJhMzMxYTISWwgNEAAaIHdhbmRvdWppYV9wY193YW5kb3VqaWEyX2hvbWVwYWdlIiB3YW5kb3VqaWFfcGNfd2FuZG91amlhMl9ob21lcGFnZTIFNS40LjE40kVCBXpoX0NOSAAaFAi41cX3AhDk0sWCjyoY+6z7go8qIh4IARAAGgxDSElOQSBNT0JJTEUiCjM2NzA5MzA0MzAqCwoJMTg0NDQ4NzA1KjMyMRACGAAgJTIbEBAYFyIMaGFzX3Nob3J0Y3V0MgdzdWNjZXNzOgxyZXN1bHQ6ZmFsc2UyBFICCAE="
    // val msgBytes = Base64.getDecoder.decode(msg)

    val taskEvt: LogEventPackage.TaskEvent = LogEventPackage.TaskEvent.newBuilder()
      .setAction(Action.PUSH)
      .setStatus(Status.READY)
      .setResult(Result.SUCCESS)
      .setResultInfo("{\"push2_title\":\"你有苹果电脑、iPhone 未领取\",\"push2_id\":\"1496468844125636434\"}")
      .build()
    val taskEvtPkg = LogEventPackage.EventPackage.newBuilder()
      .setTaskEvent(taskEvt)
      .build()

    val idPkg = IdPackage.newBuilder().setIdentity("xxxx").build()
    val cltPkg = ClientPackage.newBuilder().build()
    val timePkg = TimePackage.newBuilder()
      .setServerTimestamp(System.currentTimeMillis)
      .build()

    val commonPkg = LogCommonPackage.CommonPackage.newBuilder()
      .setIdPackage(idPkg)
      .setClientPackage(cltPkg)
      .setTimePackage(timePkg)
      .build()

    val rptEvt = LogMuce.LogReportEvent.newBuilder()
      .setEventPackage(taskEvtPkg)
      .setCommonPackage(commonPkg)
      .setLocalIncrementId(1L)
      .build()

    val pbBytes = rptEvt.toByteArray

    (1 to 100) foreach { _ => producer.send(new KeyedMessage[String, Array[Byte]](PushCtrParser.topic, pbBytes)) }
  }

  override def afterAll() {
    producer.close()
    kafkaServer.shutdown()
    zkClient.close()
    zkServer.shutdown()
    TestKit.shutdownActorSystem(system)
  }

  "PushCtrInbound" must {

    "start and stop" in {

      val pushCtrSupervisor = system.actorOf(PushCtrSupervisor.props)
      ClusterSharding(system).start(PushCtr.shardName, Some(PushCtr.props(pushCtrSupervisor)), PushCtr.idExtractor, PushCtr.shardResolver)
      val resolver = ClusterSharding(system).shardRegion(PushCtr.shardName)

      val consumerSupervisor = system.actorOf(PushCtrConsumerSupervisor.props(resolver))

      println("Starting PushCtr Inbound...")

      Thread.sleep(1000 * 1000)
    }
  }
}
