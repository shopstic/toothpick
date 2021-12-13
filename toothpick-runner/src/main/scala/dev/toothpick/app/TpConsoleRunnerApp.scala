package dev.toothpick.app

import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.util.ZTraceConcisePrinter
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.app.TpConsoleRunnerStageApp.stderrLogRouterLayer
import dev.toothpick.exporter.TpJunitXmlExporter
import dev.toothpick.proto.api.{TpRunStage, TpTest}
import dev.toothpick.reporter.{TpConsoleReporter, TpReporterConfig}
import dev.toothpick.runner.TpRunner.TpRunnerConfig
import dev.toothpick.runner.TpRunnerApiClient.TpRunnerApiClientConfig
import dev.toothpick.runner.{TpRunner, TpRunnerApiClient}
import logstage.Log
import pureconfig.ConfigReader
import scalapb.json4s.JsonFormat
import zio.blocking.effectBlocking
import zio.console.putStrLn
import zio.{ExitCode, Task, UIO, URIO}

import java.nio.file.{Files, Paths}

object TpConsoleRunnerApp extends zio.App {
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

  private def runWithStage(stage: TpRunStage) = {
    for {
      zlogger <- IzLogging.zioLogger
      appConfig <- TypedConfig.get[AppConfig]
      runnerState <- TpRunner.run(stage)
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
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val main = args match {
      case file :: Nil =>
        for {
          raw <- effectBlocking(Files.readString(Paths.get(file)))
          stage <- Task(JsonFormat.fromJsonString[TpRunStage](raw))
          ret <- runWithStage(stage)
        } yield ret

      case _ =>
        IzLogging
          .zioLogger
          .flatMap(_.error(s"Path to a stage file (in JSON) is required as the only CLI argument. Got $args"))
          .as(ExitCode(1))
    }

    val apiClientLayer = (for {
      appConfig <- TypedConfig.get[AppConfig].toManaged_
      client <- TpRunnerApiClient.managed(appConfig.apiClient)
    } yield client).toLayer

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
