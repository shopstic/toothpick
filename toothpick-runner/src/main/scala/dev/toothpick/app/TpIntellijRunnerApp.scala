package dev.toothpick.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogTemplates, IzLogging, IzLoggingRouter}
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
import izumi.logstage.api.logger.{LogRouter, LogSink}
import izumi.logstage.api.rendering.RenderingPolicy
import izumi.logstage.api.routing.ConfigurableLogRouter
import logstage.{ConsoleSink, Log}
import pureconfig.ConfigConvert
import zio.console.putStrLn
import zio.{ExitCode, Task, URIO, ZLayer}

object TpIntellijRunnerApp extends zio.App {
  final case class AppConfig(
    apiClient: TpRunnerApiClientConfig,
    runner: TpRunnerConfig,
    reporter: TpReporterConfig
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

    val logRouterLayer = ZLayer.succeed(new IzLoggingRouter.Service {
      override def create(threshold: Log.Level, sinks: Seq[LogSink]): LogRouter = {
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
      }
    })

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
      context <- Task(TpIntellijTestRunArgsParser.parse(args))
      appConfig <- TypedConfig.get[AppConfig]
      runnerState <- TpRunner.run(context, appConfig.runner)
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
        apiClientLayer
      )
      .orDie

    (before *> app *> after).as(ExitCode(0))
  }
}
