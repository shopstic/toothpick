addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("com.codecommit" % "sbt-github-packages" % "0.5.2")
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "2.0.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.2")
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.15")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.8.1")

addDependencyTreePlugin

libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.5.0"
