package dev.toothpick.exp

import zio.duration._
import zio.{ExitCode, URIO, ZIO}

import java.time.LocalDateTime

object ZioTestInterruption extends zio.App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    def test(name: String) = {
      ZIO
        .effectSuspend(zio.console.putStrLn(s"$name ${LocalDateTime.now}"))
        .delay(1.second)
        .repeatN(10)
    }

    val app = for {
      fib <- test("foo")
        .raceFirst(test("bar").uninterruptible).forkDaemon
      _ <- fib.join.raceFirst(ZIO.unit.delay(3.seconds))
    } yield ()

    app
      .forkDaemon
      .flatMap(_.join)
      .raceFirst(ZIO.unit.delay(3.seconds))
      .interruptAllChildren
      .as(ExitCode(0))
      .orDie
  }
}
