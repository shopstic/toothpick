package dev.toothpick.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogging, IzLoggingRouter}
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.chopsticks.util.config.PureconfigLoader.PureconfigLoadFailure
import dev.toothpick.reporter.TpConsoleReporter
import dev.toothpick.reporter.TpConsoleReporter.TpConsoleReporterConfig
import dev.toothpick.runner.TpRunnerApiClient.TpRunnerApiClientConfig
import dev.toothpick.runner.{TpRunner, TpRunnerApiClient}
import logstage.Log
import pureconfig.ConfigConvert
import zio.{ExitCode, URIO, ZIO}

import java.util.UUID

object TpConsoleReporterApp extends zio.App {
  final case class AppConfig(
    runId: UUID,
    apiClient: TpRunnerApiClientConfig,
    reporter: TpConsoleReporterConfig
  )

  object AppConfig {
    //noinspection TypeAnnotation
    implicit lazy val configConvert = {
      import dev.chopsticks.util.config.PureconfigConverters._
      ConfigConvert[AppConfig]
    }
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val apiClientLayer = (for {
      appConfig <- TypedConfig.get[AppConfig].toManaged_
      client <- TpRunnerApiClient.managed(appConfig.apiClient)
    } yield client).toLayer

    val main = for {
      appConfig <- TypedConfig.get[AppConfig]
      runnerState <- TpRunner.fetchState(appConfig.runId)
      _ <- TpConsoleReporter.report(runnerState, appConfig.reporter)
    } yield ExitCode(0)

    import zio.magic._

    main
      .interruptAllChildrenPar
      .injectSome[zio.ZEnv](
        HoconConfig.live(Some(this.getClass)),
        TypedConfig.live[AppConfig](logLevel = Log.Level.Info),
        IzLoggingRouter.live,
        IzLogging.live(),
        apiClientLayer
      )
      .catchSome {
        case _: PureconfigLoadFailure => ZIO.succeed(ExitCode(1))
      }
      .orDie
  }
}
