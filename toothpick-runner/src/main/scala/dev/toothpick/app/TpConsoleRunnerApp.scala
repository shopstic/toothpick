package dev.toothpick.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogTemplates, IzLogging, IzLoggingRouter}
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.exporter.TpJunitXmlExporter
import dev.toothpick.proto.api.TpTest
import dev.toothpick.reporter.{TpConsoleReporter, TpReporterConfig}
import dev.toothpick.runner.TpRunner.TpRunnerConfig
import dev.toothpick.runner.TpRunnerApiClient.TpRunnerApiClientConfig
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser
import dev.toothpick.runner.{TpRunner, TpRunnerApiClient}
import izumi.logstage.api.logger.LogSink
import izumi.logstage.api.rendering.RenderingPolicy
import izumi.logstage.api.routing.ConfigurableLogRouter
import logstage.{ConsoleSink, Log}
import pureconfig.ConfigReader
import zio.console.putStrLn
import zio.{ExitCode, Task, ULayer, URIO, ZLayer}

object TpConsoleRunnerApp extends zio.App {
  final case class AppConfig(
    apiClient: TpRunnerApiClientConfig,
    runner: TpRunnerConfig,
    reporter: TpReporterConfig
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
      result <- TpConsoleReporter.report(runnerState, appConfig.reporter)
      (hierarchy, maybeRunReport) = result
      failures =
        maybeRunReport.map(_.reports.values.filter(_.outcome.isFailure).toList).getOrElse(List.empty)
      failedCount = failures.size
      _ <- {
        val message =
          s"""
               |--------------------------------------------------------------------------------------------------------------
               |The following ${failedCount} test${if (failedCount > 1) "s" else ""} failed:
               |${failures.zipWithIndex.map { case (report, index) =>
            hierarchy.nodeMap(report.nodeId) match {
              case test: TpTest => s"  ${index + 1}. ${test.fullName}"
              case _ => ???
            }
          }.mkString("\n")}
               |--------------------------------------------------------------------------------------------------------------
               |Use this env variable to replay the report in Intellij:
               | 
               |TOOTHPICK_REPORT=${runnerState.runId}
               |""".stripMargin

        zlogger.error(s"${message -> "" -> null}")
      }.when(failedCount > 0)
      junitXml <- Task {
        maybeRunReport match {
          case Some(report) =>
            TpJunitXmlExporter.toJunitXml(hierarchy, report)
          case None =>
            TpJunitXmlExporter.empty
        }
      }
      _ <- putStrLn(
        s"""<?xml version="1.0" encoding="UTF-8"?>
           |${junitXml.toString}
           |""".stripMargin
      )
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
