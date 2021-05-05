package dev.toothpick.exporter

import dev.toothpick.proto.api.{TpTest, TpTestSuite}
import dev.toothpick.reporter.TpConsoleReporter.RunReport
import dev.toothpick.reporter.TpReporter.{TestFailed, TestIgnored, TestPassed, TestReport}
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
    disabled: Int,
    durationSeconds: Double
  )

  val empty: Elem = <testsuites tests="0" failures="0" time="0"></testsuites>

  def toJunitXml(hierarchy: TpTestHierarchy, runReport: RunReport): Elem = {
    val nodeMap = hierarchy.nodeMap

    @tailrec
    def updateSuiteMap(
      node: TestNode,
      map: Map[Int, JunitXmlTestSuite],
      report: TestReport
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
            disabled = 0,
            durationSeconds = 0d
          )
        )

        val durationSeconds = (report.endTime.toEpochMilli - report.startTime.toEpochMilli).toDouble / 1000

        updateSuiteMap(
          node = parentNode,
          map = map.updated(
            key = parentId,
            value = currentElement.copy(
              tests = currentElement.tests + 1,
              failures = currentElement.failures + (if (report.outcome.isInstanceOf[TestFailed]) 1 else 0),
              disabled = currentElement.disabled + (if (report.outcome == TestIgnored) 1 else 0),
              durationSeconds = currentElement.durationSeconds + durationSeconds
            )
          ),
          report = report
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
        updateSuiteMap(test, map, runReport.reports(test.id))
      }

    val totalTests = allTests.size
    val totalFailures = runReport.reports.count(_._2.outcome.isInstanceOf[TestFailed])
    val totalDisabled = runReport.reports.count(_._2.outcome == TestIgnored)
    val totalDurationSeconds = runReport.reports.map { case (_, outcome) =>
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
          val report = runReport.reports(test.id)
          val durationSeconds = (report.endTime.toEpochMilli - report.startTime.toEpochMilli).toDouble / 1000

          val outcomeElem = report.outcome match {
            case TestFailed(message, details) =>
              <failure message={message}>{details}</failure>
            case TestIgnored =>
              <skipped />
            case TestPassed =>
              NodeSeq.Empty
          }

          <testcase name={test.fullName} classname={test.className} time={durationSeconds.toString}>
            {outcomeElem}
            {stdoutElem}
            {stderrElem}
          </testcase>

        case suite =>
          val meta = suiteMap(suite.id)
          <testsuite id={meta.id.toString} name={meta.name} 
                     tests={meta.tests.toString} failures={meta.failures.toString} 
                     disabled={meta.disabled.toString} time={meta.durationSeconds.toString}>
            {hierarchy.topDownNodeMap.getOrElse(suite.id, Set.empty).map(id => render(nodeMap(id)))}
            {stdoutElem}
            {stderrElem}
          </testsuite>
      }
    }

    <testsuites tests={totalTests.toString} failures={totalFailures.toString} 
                disabled={totalDisabled.toString} time={totalDurationSeconds.toString}>
      {allSuites.map(render)}
    </testsuites>
  }

}
