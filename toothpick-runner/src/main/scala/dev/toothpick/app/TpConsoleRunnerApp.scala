package dev.toothpick.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogTemplates, IzLogging, IzLoggingRouter}
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.runner.TpRunner.TpRunnerConfig
import dev.toothpick.runner.TpRunnerApiClient.TpRunnerApiClientConfig
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser
import dev.toothpick.runner.{TpRunner, TpRunnerApiClient}
import izumi.logstage.api.logger.LogSink
import izumi.logstage.api.rendering.RenderingPolicy
import izumi.logstage.api.routing.ConfigurableLogRouter
import logstage.{ConsoleSink, Log}
import pureconfig.ConfigConvert
import zio.console.putStrLn
import zio.{ExitCode, Task, ULayer, URIO, ZLayer}

object TpConsoleRunnerApp extends zio.App {
  final case class AppConfig(
    apiClient: TpRunnerApiClientConfig,
    runner: TpRunnerConfig
  )

  object AppConfig {
    //noinspection TypeAnnotation
    implicit lazy val configConvert = {
      import dev.chopsticks.util.config.PureconfigConverters._
      ConfigConvert[AppConfig]
    }
  }

  val stderrLogRouterLayer: ULayer[IzLoggingRouter] = ZLayer.succeed((threshold: Log.Level, sinks: Seq[LogSink]) => {
    val modifiedSinks = sinks.map { sink: LogSink =>
      sink match {
        case _: ConsoleSink =>
          val renderingPolicy = RenderingPolicy.coloringPolicy(Some(IzLogTemplates.consoleLayout))
          new LogSink {
            override def flush(e: Log.Entry): Unit = {
              System.err.println(renderingPolicy.render(e))
            }

            override def sync(): Unit = {
              System.err.flush()
            }
          }
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

    val main = for {
      zlogger <- IzLogging.zioLogger
      context <- Task(TpIntellijTestRunArgsParser.parse(args))
      appConfig <- TypedConfig.get[AppConfig]
      runnerState <- TpRunner.run(context, appConfig.runner)
      _ <- zlogger.info(s"Run has started with ${runnerState.runId}")
      _ <- putStrLn(runnerState.runId.toString)
    } yield ExitCode(0)

    import zio.magic._

    main
      .interruptAllChildrenPar
      .injectSome[zio.ZEnv](
        HoconConfig.live(Some(this.getClass)),
        TypedConfig.live[AppConfig](logLevel = Log.Level.Info),
        stderrLogRouterLayer,
        IzLogging.live(),
        apiClientLayer
      )
      .orDie
  }
}
