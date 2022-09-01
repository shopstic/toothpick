package dev.toothpick.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.util.ZTraceConcisePrinter
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.proto.api.TpTestSuite
import dev.toothpick.reporter.{TpIntellijReporter, TpReporterConfig}
import dev.toothpick.runner.TpRunnerApiClient.TpRunnerApiClientConfig
import dev.toothpick.runner.TpRunnerUtils.{ROOT_NODE_ID, RUNNER_NODE_ID}
import dev.toothpick.runner.intellij.TpIntellijServiceMessageRenderer.{render, renderStartEvent}
import dev.toothpick.runner.intellij.TpIntellijServiceMessages.{Attrs, Names}
import dev.toothpick.runner.{TpRunner, TpRunnerApiClient}
import logstage.Log
import pureconfig.ConfigReader
import pureconfig.generic.ProductHint
import zio.console.putStrLn
import zio.{ExitCode, UIO, URIO}
import dev.chopsticks.fp.akka_env.AkkaEnv

import java.util.UUID

object TpIntellijReporterApp extends zio.App {
  final case class AppConfig(
    runId: UUID,
    apiClient: TpRunnerApiClientConfig,
    reporter: TpReporterConfig
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

    val before = putStrLn(renderStartEvent(TpTestSuite(
      name = "Toothpick Runner",
      id = RUNNER_NODE_ID,
      parentId = ROOT_NODE_ID,
      duplicateSeq = 0,
      hasFilters = false
    )))

    val after = putStrLn(render(
      Names.TEST_SUITE_FINISHED,
      Map(
        Attrs.NODE_ID -> RUNNER_NODE_ID.toString
      )
    ))

    val main = for {
      appConfig <- TypedConfig.get[AppConfig]
      runnerState <- TpRunner.fetchState(appConfig.runId)
      _ <- TpIntellijReporter.report(runnerState, appConfig.reporter)
    } yield ()

    import zio.magic._

    val app = main
      .interruptAllChildrenPar
      .injectSome[zio.ZEnv](
        HoconConfig.live(Some(this.getClass)),
        TypedConfig.live[AppConfig](logLevel = Log.Level.Info),
        TpIntellijRunnerApp.logRouterLayer,
        IzLogging.live(),
        AkkaEnv.live(),
        apiClientLayer
      )

    (before *> app *> after)
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
