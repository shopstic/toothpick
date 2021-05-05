package dev.toothpick.reporter

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api.{TpTest, TpTestSuite}
import dev.toothpick.reporter.TpReporter.{TestOutcome, TestOutputLine}
import dev.toothpick.runner.TpRunner.TpRunnerState
import dev.toothpick.runner.TpRunnerUtils.{
  ROOT_NODE_ID,
  SuitePerProcessDistribution,
  TestNode,
  TestNodeOps,
  TestPerProcessDistribution
}
import zio.clock.Clock
import zio.duration._
import zio.stream.{ZStream, ZTransducer}
import zio.{Chunk, Schedule, UIO, ZIO}

import scala.annotation.tailrec
import scala.xml.{Elem, NodeSeq}

object TpConsoleReporter {
  final case class TestReport(
    outcome: TestOutcome,
    outputs: Chunk[TestOutputLine]
  )

  final case class RunReport(
    outputs: Map[Int, Chunk[TestOutputLine]] = Map.empty,
    outcomes: Map[Int, TestOutcome] = Map.empty
  )

  final case class JunitXmlTestSuite(
    id: Int,
    name: String,
    tests: Int,
    failures: Int,
    durationSeconds: Double
  )

  private def toJunitXml(hierarchy: TpTestHierarchy, runReport: RunReport): Elem = {
    val nodeMap = hierarchy.nodeMap

    @tailrec
    def updateSuiteMap(
      node: TestNode,
      map: Map[Int, JunitXmlTestSuite],
      outcome: TestOutcome
    ): Map[Int, JunitXmlTestSuite] = {
      val parentId = node.parentId

      if (parentId == ROOT_NODE_ID) map
      else {
        val parentNode = nodeMap(parentId)
        val currentElement = map.getOrElse(
          parentId,
          JunitXmlTestSuite(
            id = parentNode.id,
            name = parentNode.name,
            tests = 0,
            failures = 0,
            durationSeconds = 0d
          )
        )

        val durationSeconds = (outcome.endTime.toEpochMilli - outcome.startTime.toEpochMilli).toDouble / 1000

        updateSuiteMap(
          node = parentNode,
          map = map.updated(
            key = parentId,
            value = currentElement.copy(
              tests = currentElement.tests + 1,
              failures = currentElement.failures + outcome.failure.fold(0)(_ => 1),
              durationSeconds = currentElement.durationSeconds + durationSeconds
            )
          ),
          outcome = outcome
        )
      }
    }

    val allTests = hierarchy.nodeMap.values
      .collect { case test: TpTest => test }
      .toList

    val allSuites = hierarchy.nodeMap.values
      .collect { case suite: TpTestSuite => suite }
      .toList

    val suiteMap = allTests
      .foldLeft(Map.empty[Int, JunitXmlTestSuite]) { (map, test) =>
        updateSuiteMap(test, map, runReport.outcomes(test.id))
      }

    val totalTests = allTests.size
    val totalFailures = runReport.outcomes.count(_._2.failure.nonEmpty)
    val totalDurationSeconds = runReport.outcomes.map { case (_, outcome) =>
      (outcome.endTime.toEpochMilli - outcome.startTime.toEpochMilli).toDouble / 1000
    }.sum

    def render(node: TestNode): NodeSeq = {
      val outputs = runReport.outputs.getOrElse(node.id, Chunk.empty)
      val (stdout, stderr) = outputs.partition(_.pipe.isStdout)
      val stdoutElem =
        if (stdout.nonEmpty)
          <system-out>{stdout.map(_.content).mkString("\n")}</system-out>
        else
          NodeSeq.Empty

      val stderrElem =
        if (stderr.nonEmpty)
          <system-err>{stderr.map(_.content).mkString("\n")}</system-err>
        else
          NodeSeq.Empty

      node match {
        case test: TpTest =>
          val outcome = runReport.outcomes(test.id)
          val durationSeconds = (outcome.endTime.toEpochMilli - outcome.startTime.toEpochMilli).toDouble / 1000

          val failureElem = outcome.failure match {
            case Some(failure) =>
              <failure message={failure.message}>{failure.details}</failure>
            case None =>
              NodeSeq.Empty
          }

          <testcase name={test.name} classname={test.className} time={durationSeconds.toString}>
            {failureElem}
            {stdoutElem}
            {stderrElem}
          </testcase>

        case suite =>
          val meta = suiteMap(suite.id)
          <testsuite id={meta.id.toString} name={meta.name} tests={meta.tests.toString} failures={
            meta.failures.toString
          } time={
            meta.durationSeconds.toString
          }>
            {hierarchy.topDownNodeMap.getOrElse(suite.id, Set.empty).map(id => render(nodeMap(id)))}
            {stdoutElem}
            {stderrElem}
          </testsuite>
      }
    }

    <testsuites tests={totalTests.toString} failures={totalFailures.toString} time={totalDurationSeconds.toString}>
      {allSuites.map(render)}
    </testsuites>
  }

  def report(
    runnerState: TpRunnerState,
    reporterConfig: TpReporterConfig
  ): ZIO[TpApiClient with Clock with IzLogging, RuntimeException, Elem] = {
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
            case outcome: TestOutcome =>
              state.copy(outcomes = state.outcomes.updated(outcome.nodeId, outcome))

            case line: TestOutputLine =>
              state.copy(outputs =
                state.outputs.updated(line.nodeId, state.outputs.getOrElse(line.nodeId, Chunk.empty) :+ line))
          }
        }
        .aggregateAsyncWithin(ZTransducer.last, Schedule.fixed(1.second))
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
    } yield {
      finalReport match {
        case Some(report) =>
          toJunitXml(initialState, report)
        case None =>
          <testsuites tests="0" failures="0" time="0"></testsuites>
      }
    }
  }
}
