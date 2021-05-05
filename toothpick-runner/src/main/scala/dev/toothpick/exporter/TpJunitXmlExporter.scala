package dev.toothpick.exporter

import dev.toothpick.proto.api.{TpTest, TpTestSuite}
import dev.toothpick.reporter.TpConsoleReporter.RunReport
import dev.toothpick.reporter.TpReporter.TestOutcome
import dev.toothpick.reporter.TpTestHierarchy
import dev.toothpick.runner.TpRunnerUtils.{ROOT_NODE_ID, TestNode, TestNodeOps}
import zio.Chunk

import scala.annotation.tailrec
import scala.xml.{Elem, NodeSeq}

object TpJunitXmlExporter {
  final case class JunitXmlTestSuite(
    id: Int,
    name: String,
    tests: Int,
    failures: Int,
    durationSeconds: Double
  )

  val empty: Elem = <testsuites tests="0" failures="0" time="0"></testsuites>

  def toJunitXml(hierarchy: TpTestHierarchy, runReport: RunReport): Elem = {
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

          <testcase name={test.fullName} classname={test.className} time={durationSeconds.toString}>
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

}
