import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import protocbridge.Target
import sbt.Keys._
import sbt._
import sbtprotoc.ProtocPlugin.autoImport.PB
import wartremover.WartRemover.autoImport._

object Build {
  val rootName = "toothpick"

  lazy val cq = taskKey[Unit]("Code quality")
  lazy val fmt = taskKey[Unit]("Code formatting")

  val scalacOptions: Seq[String] = Seq(
    "-encoding",
    "utf-8",
    "-explaintypes", // Explain type errors in more detail.
    "-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
    "-Wdead-code", // Warn when dead code is identified.
    "-Wextra-implicit", // Warn when more than one implicit parameter section is defined.
    "-Wnumeric-widen", // Warn when numerics are widened.
    "-Wvalue-discard", // Warn when non-Unit expression results are unused.
    "-Wunused:_", // Warn unused
    "-Wmacros:after",
    "-Xlint:-byname-implicit,_", // Enable all linting options except lint-byname-implicit
    "-Wconf:any:wv",
    "-Wconf:src=akka-grpc/.*:silent",
    "-Wconf:src=src_managed/.*:silent",
    "-Ypatmat-exhaust-depth",
    "40"
  )

  def createScalapbSettings(withGrpc: Boolean): Def.Setting[Seq[Target]] = {
    Compile / PB.targets += scalapb.gen(
      flatPackage = true,
      singleLineToProtoString = true,
      lenses = false,
      grpc = withGrpc
    ) -> (Compile / sourceManaged).value
  }

  def defineProject(projectName: String): Project = {
    val fullName = s"$rootName-$projectName"

    Project(projectName, file(fullName))
      .settings(
        name := fullName,
        Build.cq := {
          (Compile / scalafmtCheck).value
          (Test / scalafmtCheck).value
          val _ = (Compile / scalafmtSbtCheck).value
        },
        Build.fmt := {
          (Compile / scalafmt).value
          (Test / scalafmt).value
          (Compile / scalafmtSbt).value
        },
        Compile / compile / wartremoverErrors ++= Seq(
          Wart.AnyVal,
          Wart.FinalCaseClass,
          Wart.FinalVal,
          Wart.JavaConversions,
          Wart.LeakingSealed,
          Wart.NonUnitStatements
        ),
        wartremoverExcluded += sourceManaged.value,
        wartremoverExcluded += baseDirectory.value / "target" / "scala-2.13" / "akka-grpc",
        Compile / doc / sources := Seq.empty,
        Compile / packageDoc / publishArtifact := false
      )
  }

}
