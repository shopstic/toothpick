package dev.toothpick.runner.app
import zio.{ExitCode, URIO, ZEnv}

object TpCiRunnerApp extends zio.App {
  override def run(args: List[String]): URIO[ZEnv, ExitCode] = ???
}
