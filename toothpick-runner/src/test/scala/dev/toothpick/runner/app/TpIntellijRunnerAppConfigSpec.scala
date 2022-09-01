package dev.toothpick.runner.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogging, IzLoggingRouter}
import dev.toothpick.app.TpIntellijRunnerApp
import logstage.Log
import zio.{Cause, ZLayer}
import zio.magic._
import zio.test.Assertion._
import zio.test.{DefaultRunnableSpec, _}
import dev.chopsticks.fp.akka_env.AkkaEnv

object TpIntellijRunnerAppConfigSpec extends DefaultRunnableSpec {
  implicit class ToTestZLayer[RIn, ROut](layer: ZLayer[RIn, Throwable, ROut]) {
    def orFail: ZLayer[RIn, TestFailure[Throwable], ROut] = layer.mapError(e => TestFailure.Runtime(Cause.fail(e)))
  }

  // noinspection TypeAnnotation
  override def spec = suite("TpRunnerAppConfigSpec")(
    testM("should load default config from classpath") {
      for {
        config <- TypedConfig.get[TpIntellijRunnerApp.AppConfig]
      } yield assert(config.apiClient.serverHost.value)(equalTo("localhost"))
    }
  )
    .injectShared(
      IzLoggingRouter.live,
      AkkaEnv.live().orFail,
      IzLogging.live().orFail,
      HoconConfig.live(Some(TpIntellijRunnerApp.getClass)).orFail,
      TypedConfig.live[TpIntellijRunnerApp.AppConfig](logLevel = Log.Level.Debug).orFail
    )
}
