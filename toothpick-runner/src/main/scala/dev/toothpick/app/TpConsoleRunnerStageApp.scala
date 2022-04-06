package dev.toothpick.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogTemplates, IzLogging, IzLoggingRouter}
import dev.chopsticks.fp.util.ZTraceConcisePrinter
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.reporter.TpReporterConfig
import dev.toothpick.runner.TpRunner
import dev.toothpick.runner.TpRunner.TpRunnerConfig
import dev.toothpick.runner.TpRunnerApiClient.TpRunnerApiClientConfig
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser
import izumi.logstage.api.logger.LogSink
import izumi.logstage.api.rendering.RenderingPolicy
import izumi.logstage.api.routing.ConfigurableLogRouter
import logstage.{ConsoleSink, Log}
import pureconfig.ConfigReader
import scalapb.json4s.JsonFormat
import zio.console.putStrLn
import zio.{ExitCode, Task, UIO, ULayer, URIO, ZLayer}
import dev.chopsticks.fp.akka_env.AkkaEnv

object TpConsoleRunnerStageApp extends zio.App {
  final case class AppConfig(
    apiClient: TpRunnerApiClientConfig,
    reporter: TpReporterConfig,
    runner: TpRunnerConfig
  )

  object AppConfig {
    //noinspection TypeAnnotation
    implicit lazy val configReader = {
      import dev.chopsticks.util.config.PureconfigConverters._
      ConfigReader[AppConfig]
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
    val main = for {
      context <- Task(TpIntellijTestRunArgsParser.parse(args))
      appConfig <- TypedConfig.get[AppConfig]
      runnerStage <- TpRunner.createStage(context, appConfig.runner)
      _ <- putStrLn(JsonFormat.toJsonString(runnerStage))
    } yield ExitCode(0)

    import zio.magic._

    main
      .interruptAllChildrenPar
      .injectSome[zio.ZEnv](
        HoconConfig.live(Some(this.getClass)),
        TypedConfig.live[AppConfig](logLevel = Log.Level.Info),
        stderrLogRouterLayer,
        IzLogging.live(),
        AkkaEnv.live()
      )
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
