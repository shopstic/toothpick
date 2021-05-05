package dev.toothpick.reporter

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.proto.api.TpTest
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.reporter.TpReporter.{TestOutcome, TestOutputLine}
import dev.toothpick.runner.TpRunner.TpRunnerState
import dev.toothpick.runner.TpRunnerUtils.{SuitePerProcessDistribution, TestPerProcessDistribution}
import eu.timepit.refined.types.string.NonEmptyString
import zio.clock.Clock
import zio.duration._
import zio.stream.{ZStream, ZTransducer}
import zio.{Chunk, Schedule, UIO, ZIO}

object TpConsoleReporter {
  final case class TpConsoleReporterConfig(logOnlyFailed: Boolean, junitXmlOutputPath: NonEmptyString)

  final case class TestReport(
    outcome: TestOutcome,
    outputs: Chunk[TestOutputLine]
  )

  final case class ConsoleReporterState(
    outputs: Map[Int, Chunk[TestOutputLine]] = Map.empty,
    outcomes: Map[Int, TestOutcome] = Map.empty
  )

  private def toJunitXml(state: ConsoleReporterState) = {}

  def report(
    runnerState: TpRunnerState,
    reporterConfig: TpConsoleReporterConfig
  ): ZIO[TpApiClient with Clock with IzLogging, RuntimeException, Unit] = {
    for {
      zlogger <- IzLogging.zioLogger
      initialState <- UIO(TpReporterState.create(runnerState.nodeMap))
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

      finalState <- ZStream
        .mergeAllUnbounded()(streams: _*)
        .scan(ConsoleReporterState()) { (state, event) =>
          event match {
            case outcome: TestOutcome =>
              state.copy(outcomes = state.outcomes.updated(outcome.nodeId, outcome))

            case line: TestOutputLine =>
              state.copy(outputs =
                state.outputs.updated(line.nodeId, state.outputs.getOrElse(line.nodeId, Chunk.empty) :+ line))
          }
        }
        .aggregateAsync(ZTransducer.last)
        .schedule(Schedule.fixed(1.second))
        .tap {
          case Some(state) =>
            val completed = s"${state.outcomes.size}/$testCount"
            val succeeded = state.outcomes.values.count(_.failure.isEmpty)
            val failed = state.outcomes.values.count(_.failure.nonEmpty)

            zlogger.info(s"Run progress $completed $succeeded $failed")
          case _ =>
            ZIO.unit
        }
        .runLast
        .map(_.flatten)
    } yield ()
  }
}
