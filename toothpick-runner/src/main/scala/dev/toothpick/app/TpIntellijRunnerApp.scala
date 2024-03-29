package dev.toothpick.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogTemplates, IzLogging, IzLoggingRouter}
import dev.chopsticks.fp.util.ZTraceConcisePrinter
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.proto.api.TpTestSuite
import dev.toothpick.reporter.{TpIntellijReporter, TpReporterConfig}
import dev.toothpick.runner.TpRunner.TpRunnerConfig
import dev.toothpick.runner.TpRunnerApiClient.TpRunnerApiClientConfig
import dev.toothpick.runner.TpRunnerUtils.{ROOT_NODE_ID, RUNNER_NODE_ID}
import dev.toothpick.runner.intellij.TpIntellijServiceMessageRenderer.{render, renderStartEvent}
import dev.toothpick.runner.intellij.TpIntellijServiceMessages.{Attrs, Names}
import dev.toothpick.runner.intellij.{TpIntellijServiceMessageRenderer, TpIntellijTestRunArgsParser}
import dev.toothpick.runner.{TpRunner, TpRunnerApiClient}
import izumi.logstage.api.logger.LogSink
import izumi.logstage.api.rendering.RenderingPolicy
import izumi.logstage.api.routing.ConfigurableLogRouter
import logstage.{ConsoleSink, Log}
import pureconfig.ConfigReader
import zio.console.putStrLn
import zio.{ExitCode, Task, UIO, ULayer, URIO, ZLayer}
import dev.chopsticks.fp.akka_env.AkkaEnv

object TpIntellijRunnerApp extends zio.App {
  final case class AppConfig(
    apiClient: TpRunnerApiClientConfig,
    runner: TpRunnerConfig,
    reporter: TpReporterConfig
  )

  object AppConfig {
    // noinspection TypeAnnotation
    implicit lazy val configReader = {
      import dev.chopsticks.util.config.PureconfigConverters._
      ConfigReader[AppConfig]
    }
  }

  val logRouterLayer: ULayer[IzLoggingRouter] = ZLayer.succeed((threshold: Log.Level, sinks: Seq[LogSink]) => {
    val modifiedSinks = sinks.map { sink: LogSink =>
      sink match {
        case _: ConsoleSink =>
          val renderingPolicy = RenderingPolicy.coloringPolicy(Some(IzLogTemplates.consoleLayout))
          val tcRenderingPolicy = new RenderingPolicy {
            override def render(entry: Log.Entry): String = {
              TpIntellijServiceMessageRenderer.render(
                Names.TEST_STD_OUT,
                Map(
                  Attrs.NODE_ID -> RUNNER_NODE_ID.toString,
                  Attrs.OUT -> s"${renderingPolicy.render(entry)}\n"
                )
              )
            }
          }

          ConsoleSink(tcRenderingPolicy)
        case sink => sink
      }
    }

    ConfigurableLogRouter(threshold, modifiedSinks)
  })

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
      zlogger <- IzLogging.zioLogger
      context <- Task(TpIntellijTestRunArgsParser.parse(args))
      appConfig <- TypedConfig.get[AppConfig]
      runnerStage <- TpRunner.createStage(context, appConfig.runner)
      runnerState <- TpRunner.run(runnerStage)
      _ <- zlogger.info(s"Run started with ${runnerState.runId}")
      _ <- TpIntellijReporter.report(runnerState, appConfig.reporter)
    } yield ()

    import zio.magic._

    val app = main
      .interruptAllChildrenPar
      .injectSome[zio.ZEnv](
        HoconConfig.live(Some(this.getClass)),
        TypedConfig.live[AppConfig](logLevel = Log.Level.Info),
        logRouterLayer,
        IzLogging.live(),
        AkkaEnv.live(),
        apiClientLayer
      )

    (before *> app *> after).as(ExitCode(0))
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
