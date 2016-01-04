import com.typesafe.sbt.packager.archetypes.JavaAppPackaging
import sbt.Keys._
import sbt._

object Dependencies {
    val AKKA_VERSION = "2.3.11"
    val SPRAY_VERSION = "1.3.3"
    val SLF4J_VERSION = "1.7.7"
    val KAFKA_VERSION = "0.8.2.1"
    val AKKA_STREAM_VERSION = "1.0"

    val log = Seq("org.slf4j" % "slf4j-api" % SLF4J_VERSION,
                  "org.slf4j" % "jcl-over-slf4j" % SLF4J_VERSION,
                  "org.slf4j" % "log4j-over-slf4j" % SLF4J_VERSION,
                  "ch.qos.logback" % "logback-classic" % "1.1.2",
                  "com.typesafe.akka" %% "akka-slf4j" % AKKA_VERSION)

    val test = Seq("com.typesafe.akka" %% "akka-testkit" % AKKA_VERSION % Test,
                   "com.typesafe.akka" %% "akka-multi-node-testkit" % AKKA_VERSION % Test,
                   "org.scalamock" %% "scalamock-scalatest-support" % "3.2.1" % Test,
                   "org.scalatest" %% "scalatest" % "2.2.4" % Test)

    val akka = Seq("com.typesafe.akka" %% "akka-actor" % AKKA_VERSION,
                   "com.typesafe.akka" %% "akka-contrib" % AKKA_VERSION,
                   "com.typesafe.akka" %% "akka-persistence-experimental" % AKKA_VERSION)

    val basic = log ++ test ++ akka

    val kafka = Seq("org.apache.kafka" %% "kafka" % KAFKA_VERSION excludeAll(
                        ExclusionRule(organization = "com.sun.jdmk"),
                        ExclusionRule(organization = "com.sun.jmx"),
                        ExclusionRule(organization = "log4j"),
                        ExclusionRule(organization = "org.slf4j"),
                        ExclusionRule(organization = "javax.jms")),
                    "org.apache.kafka" %% "kafka" % KAFKA_VERSION % Test classifier "test")

    val hbaseVersion = "0.98.10-hadoop2"
    val hbasePersistence = "akka-persistence-hbase"

    val hbase = Seq("org.apache.hbase"  % "hbase-common"  % hbaseVersion excludeAll (
                        ExclusionRule(organization = "org.mortbay.jetty"),
                        ExclusionRule(organization = "com.sun.jersey"),
                        ExclusionRule(organization = "org.apache.zookeeper"),
                        ExclusionRule(organization = "tomcat"),
                        ExclusionRule(organization = "commons-logging"),
                        ExclusionRule(organization = "log4j"),
                        ExclusionRule(organization = "org.slf4j")),
                    "org.apache.hbase"  % "hbase-client"  % hbaseVersion excludeAll (
                        ExclusionRule(organization = "org.mortbay.jetty"),
                        ExclusionRule(organization = "com.sun.jersey"),
                        ExclusionRule(organization = "org.apache.zookeeper"),
                        ExclusionRule(organization = "tomcat"),
                        ExclusionRule(organization = "commons-logging"),
                        ExclusionRule(organization = "log4j"),
                        ExclusionRule(organization = "org.slf4j")),
                    "pl.project13.scala" %% hbasePersistence % "0.4.2-SNAPSHOT" excludeAll (
                        ExclusionRule(organization = "log4j"),
                        ExclusionRule(organization = "org.slf4j"),
                        ExclusionRule(organization = "jline"),
                        ExclusionRule(organization = "junit")),
                    "org.hbase" % "asynchbase" % "1.5.0" excludeAll ExclusionRule(organization = "org.slf4j"))

    val akka_http_routing = Seq("com.typesafe.akka" %% "akka-stream-experimental"             % AKKA_STREAM_VERSION,
                                "com.typesafe.akka" %% "akka-http-core-experimental"          % AKKA_STREAM_VERSION,
                                "com.typesafe.akka" %% "akka-http-experimental"               % AKKA_STREAM_VERSION,
                                "com.typesafe.akka" %% "akka-http-spray-json-experimental"    % AKKA_STREAM_VERSION,
                                "com.typesafe.akka" %% "akka-http-testkit-experimental"       % AKKA_STREAM_VERSION)

    val muce_logproto = Seq ("com.wandoujia.muce" % "logproto" % "1.5")

    val proto = Seq("com.wandoujia.statuscentre" % "akka-app-proto" % "0.1.1" excludeAll ExclusionRule(organization = "com.google.protobuf"), // exclude 2.6.1
                    "com.googlecode.protobuf-java-format" % "protobuf-java-format" % "1.2")

    val joda_time = Seq("joda-time" % "joda-time" % "2.9.1")

    val all = basic ++ kafka ++ hbase ++ muce_logproto ++ proto ++ joda_time
}

object Formatting {

    import com.typesafe.sbt.SbtScalariform
    import com.typesafe.sbt.SbtScalariform.ScalariformKeys

    val formattingPreferences = {
        import scalariform.formatter.preferences._
        FormattingPreferences()
        .setPreference(RewriteArrowSymbols, false)
        .setPreference(AlignParameters, true)
        .setPreference(AlignSingleLineCaseStatements, true)
        .setPreference(DoubleIndentClassDeclaration, true)
        .setPreference(IndentSpaces, 2)
    }

    val settings = SbtScalariform.scalariformSettings ++
        Seq(ScalariformKeys.preferences in Compile := formattingPreferences,
            ScalariformKeys.preferences in Test := formattingPreferences)

}

object Build extends sbt.Build {
    val env = sys.env.getOrElse("env", "dev")
    println(s"Building $env")

    lazy val basicSettings = Seq(organization := "com.wandoujia.io",
                                 scalaVersion := "2.11.7",
                                 scalacOptions ++= Seq("-unchecked", "-deprecation"),
                                 resolvers ++= Seq("Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases",
                                                   "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
                                                   "Wandoulabs repo" at "http://mvn.corp.wandoujia.com/nexus/content/groups/public/",
                                                   "Typesafe repo" at "http://repo.typesafe.com/typesafe/releases/",
                                                   "spray" at "http://repo.spray.io",
                                                   "spray nightly" at "http://nightlies.spray.io/",
                                                   "patriknw at bintray" at "http://dl.bintray.com/patriknw/maven",
                                                   "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven",
                                                   "cloudera-repo-releases" at "https://repository.cloudera.com/artifactory/repo/"
                                                   ) ++ Seq(Resolver.mavenLocal),
                                 fork in run := true,
                                 javacOptions ++= Seq("-source", "1.6", "-target", "1.6"),
                                 credentials += Credentials(Path.userHome / ".ivy2" / ".wdj_credentials"),
                                 checksums in update := Nil)

    lazy val akka_app = Project("akka-app", file("."))
                        .aggregate(core, inbound, webapp, service, benchmark, coldstart)
                        .settings(basicSettings: _*)
                        .settings(Formatting.settings: _*)

    lazy val core = Project("core", file("core"))
                    .settings(basicSettings: _*)
                    .settings(Formatting.settings: _*)
                    .settings(libraryDependencies ++= Dependencies.basic ++ Dependencies.joda_time)

    lazy val webapp = Project("webapp", file("webapp"))
                      .dependsOn(core)
                      .settings(basicSettings: _*)
                      .settings(Formatting.settings: _*)
                      .settings(libraryDependencies ++= Dependencies.basic ++ Dependencies.akka_http_routing ++ Dependencies.proto)

    lazy val inbound = Project("inbound", file("inbound"))
                       .dependsOn(core)
                       .settings(basicSettings: _*)
                       .settings(Formatting.settings: _*)
                       .settings(libraryDependencies ++= Dependencies.basic ++ Dependencies.kafka ++ Dependencies.muce_logproto)
                       .enablePlugins(JavaAppPackaging)

    lazy val service = Project("service", file("service"))
                       .dependsOn(core, webapp, inbound)
                       .settings(basicSettings: _*)
                       .settings(Formatting.settings: _*)
                       .settings(libraryDependencies ++= Dependencies.basic ++ Dependencies.hbase)
                       .enablePlugins(JavaAppPackaging)

    lazy val benchmark = Project("benchmark", file("benchmark"))
                       .dependsOn(core, webapp, inbound)
                       .settings(basicSettings: _*)
                       .settings(Formatting.settings: _*)
                       .settings(libraryDependencies ++= Dependencies.basic)
                       .enablePlugins(JavaAppPackaging)

    lazy val coldstart = Project("coldstart", file("coldstart"))
                         .settings(basicSettings: _*)
                         .settings(Formatting.settings: _*)
                         .settings(libraryDependencies ++= Dependencies.basic)
                         .enablePlugins(JavaAppPackaging)

    lazy val test = Project("test", file("test"))
                       .settings(basicSettings: _*)
                       .settings(Formatting.settings: _*)
                       .settings(libraryDependencies ++= Dependencies.basic)
                       .enablePlugins(JavaAppPackaging)
}
