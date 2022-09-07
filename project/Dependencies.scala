import sbt._

object Dependencies {
  val CHOPSTICKS_VERSION = "3.10.0+716-7cbf1f4-SNAPSHOT"
  val ZIO_VERSION = "1.0.15"

  lazy val akkaGrpcRuntimeDeps = Seq(
    "com.lightbend.akka.grpc" %% "akka-grpc-runtime" % "2.1.6"
  )

  lazy val betterFilesDeps = Seq(
    "com.github.pathikrit" %% "better-files" % "3.9.1"
  )

  lazy val chopsticksKvdbCodecFdbKeyDeps = Seq(
    "dev.chopsticks" %% "chopsticks-kvdb-codec-fdb-key" % CHOPSTICKS_VERSION
  )

  lazy val chopsticksKvdbCodecProtobufValueDeps = Seq(
    "dev.chopsticks" %% "chopsticks-kvdb-codec-protobuf-value" % CHOPSTICKS_VERSION
  )

  lazy val chopsticksDstreamDeps = Seq(
    "dev.chopsticks" %% "chopsticks-dstream" % CHOPSTICKS_VERSION
  )

  lazy val chopsticksZioGrpcCommonDeps = Seq(
    "dev.chopsticks" %% "chopsticks-zio-grpc-common" % CHOPSTICKS_VERSION
  )

  lazy val chopsticksKvdbFdbDeps = Seq(
    "dev.chopsticks" %% "chopsticks-kvdb-fdb" % CHOPSTICKS_VERSION
  )

  lazy val cytodynamicsNucleusDeps = Seq(
    "com.linkedin.cytodynamics" % "cytodynamics-nucleus" % "0.2.0"
  )

  lazy val fastparseDeps = Seq(
    "com.lihaoyi" %% "fastparse" % "2.3.3"
  )

  lazy val grpcNettyDeps = Seq(
    "io.grpc" % "grpc-netty" % "1.49.0"
  )

  lazy val jibDeps = Seq(
    "com.google.cloud.tools" % "jib-core" % "0.22.0"
  )

  lazy val jsoniterDeps = Seq(
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.17.1",
    "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.17.1" % "provided"
  )

  lazy val pureconfigEnumeratumDeps = Seq(
    "com.github.pureconfig" %% "pureconfig-enumeratum" % "0.17.1"
  )

  lazy val quicklensDeps = Seq(
    "com.softwaremill.quicklens" %% "quicklens" % "1.8.10"
  )

  lazy val overrideDeps = Seq(
    "com.typesafe.akka" %% "akka-discovery" % "2.6.19"
  )

  lazy val pprintDeps = Seq(
    "com.lihaoyi" %% "pprint" % "0.7.3"
  )

  lazy val scalaXmlDeps = Seq(
    // "org.scala-lang.modules" %% "scala-xml" % "1.3.0"
  )

  lazy val scalatestDeps = Seq(
    "org.scalatest" %% "scalatest" % "3.2.10"
  )

  lazy val scalapbRuntimeDeps = Seq(
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
  )

  lazy val scalapbJson4sDeps = Seq(
    "com.thesamet.scalapb" %% "scalapb-json4s" % "0.12.0"
  )

  lazy val zioProcessDeps = Seq(
    "dev.zio" %% "zio-process" % "0.6.1"
  )

  lazy val zioInteropReactivestreamsDeps = Seq(
    "dev.zio" %% "zio-interop-reactivestreams" % "1.3.12"
  )

  lazy val zioDeps = Seq(
    "io.github.kitlangton" %% "zio-magic" % "0.3.12",
    "dev.zio" %% "zio" % ZIO_VERSION,
    "dev.zio" %% "zio-test" % ZIO_VERSION % "test",
    "dev.zio" %% "zio-test-sbt" % ZIO_VERSION % "test"
  )

}
