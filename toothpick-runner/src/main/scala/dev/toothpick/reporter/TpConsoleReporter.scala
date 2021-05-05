package dev.toothpick.reporter

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.proto.api.TpTest
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.reporter.TpReporter.{TestFailed, TestIgnored, TestOutputLine, TestPassed, TestReport}
import dev.toothpick.runner.TpRunner.TpRunnerState
import dev.toothpick.runner.TpRunnerUtils.{SuitePerProcessDistribution, TestPerProcessDistribution}
import zio.clock.Clock
import zio.duration._
import zio.stream.{ZStream, ZTransducer}
import zio.{Chunk, Schedule, UIO, ZIO}

object TpConsoleReporter {
  final case class RunReport(
    outputs: Map[Int, Chunk[TestOutputLine]] = Map.empty,
    reports: Map[Int, TestReport] = Map.empty
  )

  def report(
    runnerState: TpRunnerState,
    reporterConfig: TpReporterConfig
  ): ZIO[TpApiClient with Clock with IzLogging, RuntimeException, (TpTestHierarchy, Option[RunReport])] = {
    for {
      zlogger <- IzLogging.zioLogger
      initialState <- UIO(TpTestHierarchy.create(runnerState.nodeMap))
      testCount = initialState.nodeMap.values.count {
        case _: TpTest => true
        case _ => false
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

      finalReport <- ZStream
        .mergeAllUnbounded()(streams: _*)
        .scan(RunReport()) { (state, event) =>
          event match {
            case outcome: TestReport =>
              state.copy(reports = state.reports.updated(outcome.nodeId, outcome))

            case line: TestOutputLine =>
              state.copy(outputs =
                state.outputs.updated(line.nodeId, state.outputs.getOrElse(line.nodeId, Chunk.empty) :+ line))
          }
        }
        .aggregateAsyncWithin(ZTransducer.last, Schedule.fixed(1.second))
        .tap {
          case Some(state) =>
            val completed = s"${state.reports.size}/$testCount"
            val passed = state.reports.values.count(_.outcome == TestPassed)
            val failed = state.reports.values.count(_.outcome.isInstanceOf[TestFailed])
            val ignored = state.reports.values.count(_.outcome == TestIgnored)

            zlogger.info(s"Run progress $completed $passed $failed $ignored")
          case _ =>
            ZIO.unit
        }
        .runLast
        .map(_.flatten)
    } yield initialState -> finalReport
  }
}
