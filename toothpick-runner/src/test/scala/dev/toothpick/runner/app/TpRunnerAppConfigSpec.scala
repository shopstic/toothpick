package dev.toothpick.runner.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogging, IzLoggingRouter}
import dev.toothpick.app.TpRunnerApp
import logstage.Log
import zio.{Cause, ZLayer}
import zio.magic._
import zio.test.Assertion._
import zio.test.{DefaultRunnableSpec, _}

object TpRunnerAppConfigSpec extends DefaultRunnableSpec {
  implicit class ToTestZLayer[RIn, ROut](layer: ZLayer[RIn, Throwable, ROut]) {
    def orFail: ZLayer[RIn, TestFailure[Throwable], ROut] = layer.mapError(e => TestFailure.Runtime(Cause.fail(e)))
  }

  //noinspection TypeAnnotation
  override def spec = suite("TpRunnerAppConfigSpec")(
    testM("should load default config from classpath") {
      for {
        config <- TypedConfig.get[TpRunnerApp.AppConfig]
      } yield assert(config.serverHost.value)(equalTo("localhost"))
    }
  )
    .injectSomeShared[Environment](
      IzLoggingRouter.live,
      IzLogging.live().orFail,
      HoconConfig.live(Some(TpRunnerApp.getClass)).orFail,
      TypedConfig.live[TpRunnerApp.AppConfig](logLevel = Log.Level.Debug).orFail
    )
}
