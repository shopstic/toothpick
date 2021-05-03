package dev.toothpick.reporter

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api.{TpTest, TpTestGroup, TpTestSuite}
import dev.toothpick.runner.TpRunner.TpRunnerState
import dev.toothpick.runner.TpRunnerUtils.{ReportStreamItem, SuitePerProcessDistribution, TestPerProcessDistribution}
import dev.toothpick.runner.intellij.TpIntellijServiceMessageRenderer.{render, renderStartEvent}
import dev.toothpick.runner.intellij.TpIntellijServiceMessageReporter
import dev.toothpick.runner.intellij.TpIntellijServiceMessageReporter.ReportQueueItem
import dev.toothpick.runner.intellij.TpIntellijServiceMessages.{Attrs, Names}
import zio.clock.Clock
import zio.console.Console
import zio.stream.Stream
import zio.{RIO, UIO, ZIO, ZQueue}

object TpIntellijReporter {
  def report(
    runnerState: TpRunnerState,
    reporterConfig: TpReporterConfig
  ): RIO[Console with Clock with TpApiClient with IzLogging, Unit] = {
    for {
      state <- UIO(TpReporterState.create(runnerState.hierarchy))
      reportQueue <- ZQueue.unbounded[ReportQueueItem]
      reportFib <- Stream
        .fromQueue(reportQueue)
        .mapAccum(state) { case (s, ReportQueueItem(id, maybeFailure, durationMs)) =>
          val (newState, toEmit) = TpReporterState.trimPendingMap(s, id)
          newState -> ReportStreamItem(
            nodes = toEmit,
            allDone = newState.pendingMap.isEmpty,
            failure = maybeFailure,
            durationMs = durationMs
          )
        }
        .takeUntil(_.allDone)
        .foreach { case ReportStreamItem(nodes, _, maybeFailure, durationMs) =>
          val lines = nodes.map {
            case test: TpTest =>
              maybeFailure match {
                case Some(failure) =>
                  render(
                    Names.TEST_FAILED,
                    Map(
                      Attrs.NODE_ID -> test.id.toString,
                      Attrs.MESSAGE -> failure.message, // will not work with Intellij if this property is missing altogether, but empty is fine
                      Attrs.DETAILS -> failure.details,
                      Attrs.ERROR -> "true", // error will show as red vs. warning/orange on Intellij
                      Attrs.DURATION -> durationMs.toString
                    )
                  )

                case None =>
                  render(
                    Names.TEST_FINISHED,
                    Map(
                      Attrs.NODE_ID -> test.id.toString,
                      Attrs.DURATION -> durationMs.toString
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

          UIO(lines.foreach(println))
        }
        .fork

      totalTestCount = state.hierachy.values.count {
        case _: TpTest => true
        case _ => false
      }

      _ <- UIO {
        state
          .topDownQueue
          .map(renderStartEvent)
          .prepended(render(
            Names.TEST_COUNT,
            Map(Attrs.COUNT -> totalTestCount.toString)
          ))
          .foreach(println)
      }

      _ <- ZIO.foreachPar_(runnerState.distributions) {
        case TestPerProcessDistribution(test) =>
          TpIntellijServiceMessageReporter.reportTest(
            uuid = runnerState.runId,
            test = test,
            reportQueue = reportQueue,
            onlyLogIfFailed = reporterConfig.logOnlyFailed
          )

        case SuitePerProcessDistribution(suite, tests) =>
          TpIntellijServiceMessageReporter.reportSuite(
            uuid = runnerState.runId,
            suite = suite,
            tests = tests,
            reportQueue = reportQueue,
            onlyLogIfFailed = reporterConfig.logOnlyFailed
          )
      }
      _ <- reportFib.join

    } yield ()
  }
}
