package dev.toothpick.reporter

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api.{TpTest, TpTestGroup, TpTestSuite}
import dev.toothpick.reporter.TpReporter.{TestFailed, TestIgnored, TestOutputLine, TestPassed, TestReport}
import dev.toothpick.runner.TpRunner.TpRunnerState
import dev.toothpick.runner.TpRunnerUtils.{ReportStreamItem, SuitePerProcessDistribution, TestPerProcessDistribution}
import dev.toothpick.runner.intellij.TpIntellijServiceMessageRenderer.{render, renderStartEvent}
import dev.toothpick.runner.intellij.TpIntellijServiceMessages.{Attrs, Names}
import zio.clock.Clock
import zio.console.Console
import zio.stream.ZStream
import zio.{UIO, ZIO}

object TpIntellijReporter {
  def report(
    runnerState: TpRunnerState,
    reporterConfig: TpReporterConfig
  ): ZIO[Console with TpApiClient with Clock with IzLogging, RuntimeException, Unit] = {

    for {
      initialState <- UIO(TpTestHierarchy.create(runnerState.nodeMap))

      testCount = initialState.nodeMap.values.count {
        case _: TpTest => true
        case _ => false
      }

      _ <- UIO {
        initialState
          .topDownNodeQueue
          .map(renderStartEvent)
          .prepended(render(
            Names.TEST_COUNT,
            Map(Attrs.COUNT -> testCount.toString)
          ))
          .foreach(println)
      }

      streams <- ZIO.foreachPar(runnerState.distributions) {
        case TestPerProcessDistribution(test) =>
          TpReporter.reportTest(
            uuid = runnerState.runId,
            test = test,
            onlyLogIfFailed = reporterConfig.logOnlyFailed
          )

        case SuitePerProcessDistribution(suite, tests) =>
          TpReporter.reportSuite(
            uuid = runnerState.runId,
            suite = suite,
            tests = tests,
            onlyLogIfFailed = reporterConfig.logOnlyFailed
          )
      }

      _ <- ZStream
        .mergeAllUnbounded()(streams: _*)
        .mapAccum(initialState) { case (currentState, event) =>
          event match {
            case line: TestOutputLine =>
              currentState -> Left(line)

            case report: TestReport =>
              val (newState, toEmit) = TpTestHierarchy.trimTopDownNodeMap(currentState, report.nodeId)
              newState -> Right(ReportStreamItem(
                nodes = toEmit,
                allDone = newState.topDownNodeMap.isEmpty,
                outcome = report.outcome,
                durationMs = report.endTime.toEpochMilli - report.startTime.toEpochMilli
              ))
          }
        }
        .map {
          case Left(TestOutputLine(nodeId, pipe, content)) =>
            render(
              if (pipe.isStdout) Names.TEST_STD_OUT else Names.TEST_STD_ERR,
              Map(
                Attrs.NODE_ID -> nodeId.toString,
                Attrs.OUT -> (content + "\n")
              )
            ) :: Nil

          case Right(ReportStreamItem(nodes, _, outcome, durationMs)) =>
            nodes.map {
              case test: TpTest =>
                outcome match {
                  case TestFailed(message, details) =>
                    render(
                      Names.TEST_FAILED,
                      Map(
                        Attrs.NODE_ID -> test.id.toString,
                        Attrs.MESSAGE -> message, // will not work with Intellij if this property is missing altogether, but empty is fine
                        Attrs.DETAILS -> details,
                        Attrs.ERROR -> "true", // error will show as red vs. warning/orange on Intellij
                        Attrs.DURATION -> durationMs.toString
                      )
                    )

                  case TestPassed =>
                    render(
                      Names.TEST_FINISHED,
                      Map(
                        Attrs.NODE_ID -> test.id.toString,
                        Attrs.DURATION -> durationMs.toString
                      )
                    )

                  case TestIgnored =>
                    render(
                      Names.TEST_IGNORED,
                      Map(
                        Attrs.NODE_ID -> test.id.toString,
                        Attrs.MESSAGE -> "Test ignored"
                      )
                    )
                }

              case group: TpTestGroup =>
                render(
                  Names.TEST_SUITE_FINISHED,
                  Map(
                    Attrs.NODE_ID -> group.id.toString
                  )
                )

              case suite: TpTestSuite =>
                render(
                  Names.TEST_SUITE_FINISHED,
                  Map(
                    Attrs.NODE_ID -> suite.id.toString
                  )
                )
            }
        }
        .foreach(lines => UIO(lines.foreach(println)))

    } yield ()
  }
}
