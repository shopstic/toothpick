package dev.toothpick.app

import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.util.ZTraceConcisePrinter
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.app.TpConsoleRunnerStageApp.stderrLogRouterLayer
import dev.toothpick.artifact.{TpArtifactAggregator, TpArtifactAggregatorConfig}
import dev.toothpick.runner.TpRunnerApiClient.TpRunnerApiClientConfig
import dev.toothpick.runner.{TpRunner, TpRunnerApiClient}
import logstage.Log
import pureconfig.ConfigReader
import pureconfig.generic.ProductHint
import wvlet.airframe.ulid.ULID
import zio.{ExitCode, Task, UIO, URIO, ZIO}

object TpArtifactAggregatorApp extends zio.App {
  final case class AppConfig(
    apiClient: TpRunnerApiClientConfig,
    artifactAggregator: TpArtifactAggregatorConfig
  )

  object AppConfig {
    // noinspection TypeAnnotation
    implicit lazy val configReader = {
      import dev.chopsticks.util.config.PureconfigConverters._
      implicit val hint: ProductHint[AppConfig] = ProductHint[AppConfig](allowUnknownKeys = true)
      ConfigReader[AppConfig]
    }
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val apiClientLayer = (for {
      appConfig <- TypedConfig.get[AppConfig].toManaged_
      client <- TpRunnerApiClient.managed(appConfig.apiClient)
    } yield client).toLayer

    val main = for {
      runId <- args match {
        case head :: Nil =>
          Task(ULID.fromString(head))

        case got =>
          ZIO.fail(new IllegalArgumentException(
            s"Expected exactly 1 argument for the runId in ULID format, instead got: $got"
          ))
      }
      appConfig <- TypedConfig.get[AppConfig]
      runnerState <- TpRunner.fetchState(runId)
      _ <- TpArtifactAggregator.aggregate(runnerState, appConfig.artifactAggregator, better.files.Dsl.cwd.path)
    } yield ()

    import zio.magic._

    val app = main
      .interruptAllChildrenPar
      .injectSome[zio.ZEnv](
        HoconConfig.live(Some(this.getClass)),
        TypedConfig.live[AppConfig](logLevel = Log.Level.Info),
        stderrLogRouterLayer,
        IzLogging.live(),
        AkkaEnv.live(),
        apiClientLayer
      )

    app
      .as(ExitCode(0))
      .catchAllTrace { case (e, maybeTrace) =>
        UIO {
          e.printStackTrace()
          maybeTrace.foreach { t =>
            System.err.println("\n" + ZTraceConcisePrinter.prettyPrint(t))
          }
        }.as(ExitCode(1))
      }
  }
}
