import Dependencies._

//ThisBuild / githubOwner := "shopstic"
//ThisBuild / githubRepository := "toothpick"
ThisBuild / organization := "dev.toothpick"

ThisBuild / scalaVersion := "2.13.7"

//ThisBuild / resolvers ++= Seq(
//  Resolver.githubPackages("shopstic", "chopsticks")
//)

ThisBuild / javacOptions := Seq("-encoding", "UTF-8")
ThisBuild / scalacOptions := Build.scalacOptions

ThisBuild / dependencyOverrides := Dependencies.overrideDeps
// ThisBuild / dockerApiVersion := Some(DockerApiVersion(1, 41))
// ThisBuild / dockerVersion := DockerVersion.parse("20.10.10")
ThisBuild / PB.protocVersion := "3.17.3"

lazy val api = Build
  .defineProject("api")
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    Compile / PB.targets := Seq(
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value
    ),
    libraryDependencies ++= scalapbRuntimeDeps ++ grpcNettyDeps ++ chopsticksKvdbCodecFdbKeyDeps ++
      chopsticksKvdbCodecProtobufValueDeps
  )
  .settings(Build.createScalapbSettings(withGrpc = true))

lazy val dstream = Build
  .defineProject("dstream")
  .enablePlugins(AkkaGrpcPlugin)
  .settings(
    publish / skip := true,
    akkaGrpcCodeGeneratorSettings ++= Seq("server_power_apis", "single_line_to_proto_string"),
    libraryDependencies ++= akkaGrpcRuntimeDeps ++ chopsticksKvdbCodecProtobufValueDeps
  )

lazy val server = Build
  .defineProject("server")
  .dependsOn(api, dstream)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(Build.createScalapbSettings(withGrpc = false))
  .settings(
    publish / skip := true,
    Compile / discoveredMainClasses := Seq.empty,
    Compile / mainClass := Some("dev.toothpick.app.TpMasterApp"),
    dockerExposedPorts := Seq(8080, 8081),
    dockerEntrypoint := Seq("/usr/bin/dumb-init", "--"),
    Docker / daemonUserUid := Some("1001"),
    Docker / daemonGroupGid := Some("1001"),
    Docker / daemonUser := "app",
    Docker / daemonGroup := "app",
    libraryDependencies ++= chopsticksDstreamDeps ++ chopsticksZioGrpcCommonDeps ++ chopsticksKvdbFdbDeps ++
      chopsticksKvdbCodecProtobufValueDeps ++ zioProcessDeps ++ zioInteropReactivestreamsDeps ++
      scalapbJson4sDeps ++ quicklensDeps ++ pureconfigEnumeratumDeps ++ zioDeps ++ jsoniterDeps
  )

lazy val runner = Build
  .defineProject("runner")
  .dependsOn(api)
  .enablePlugins(JavaAppPackaging)
  .settings(
    Compile / mainClass := Some("dev.toothpick.app.TpIntellijRunnerApp"),
    Compile / discoveredMainClasses := Seq.empty,
    libraryDependencies ++= scalaXmlDeps ++ jibDeps ++ betterFilesDeps ++ cytodynamicsNucleusDeps ++
      quicklensDeps ++ fastparseDeps ++ pprintDeps ++ zioDeps ++ jsoniterDeps.map(m =>
        m.withConfigurations(m.configurations.map(_ + ",test").orElse(Some("test")))
      ),
    publish / skip := true,
    testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))
  )

lazy val exp = Build
  .defineProject("exp")
  .dependsOn(server)
  .settings(
    libraryDependencies ++= zioDeps ++ cytodynamicsNucleusDeps,
    publish / skip := true
  )

lazy val root = (project in file("."))
  .settings(
    name := Build.rootName,
    publish / skip := true
  )
  .aggregate(
    api,
    dstream,
    server,
    runner,
    exp
  )
