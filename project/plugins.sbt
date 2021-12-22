addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.5")
//addSbtPlugin("com.codecommit" % "sbt-github-packages" % "0.5.2")
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "2.1.2")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.3")
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.16")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.7")

addDependencyTreePlugin

libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.5.1"
