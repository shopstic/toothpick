package dev.toothpick.runner

import cats.data.NonEmptyList
import dev.toothpick.proto.api._
import dev.toothpick.reporter.TpReporter.TestFailureDetails
import dev.toothpick.runner.intellij.TpIntellijServiceMessageParser.TC_PREFIX

import scala.annotation.tailrec

object TpRunnerUtils {
  val ROOT_NODE_ID = 0
  val RUNNER_NODE_ID = 1

  type TestNode = TpTestNode.NonEmpty

  final case class ScalaTestHierarchy(map: Map[Int, TestNode], nextId: Int)

  implicit class TestNodeOps(node: TpTestNode.NonEmpty) {
    def id: Int = node match {
      case test: TpTest => test.id
      case group: TpTestGroup => group.id
      case suite: TpTestSuite => suite.id
    }

    def parentId: Int = node match {
      case test: TpTest => test.parentId
      case group: TpTestGroup => group.parentId
      case suite: TpTestSuite => suite.parentId
    }
  }

  sealed trait TestDistribution
  final case class SuitePerProcessDistribution(suite: TpTestSuite, tests: NonEmptyList[TpTest]) extends TestDistribution
  final case class TestPerProcessDistribution(test: TpTest) extends TestDistribution

  final case class ReportStreamItem(
    nodes: List[TestNode],
    allDone: Boolean,
    failure: Option[TestFailureDetails],
    durationMs: Long
  )

  object MatchesEmptyLineStdoutReport {
    def unapply(report: TpTestReport): Boolean = {
      report.event match {
        case TpTestOutputLine(content, pipe) if pipe.isStdout && content.isEmpty => true
        case _ => false
      }
    }
  }

  object MatchesTeamCityServiceMessageEvent {
    def unapply(event: TpTestEvent): Option[String] = {
      event match {
        case TpTestOutputLine(content, pipe) if pipe.isStdout && content.startsWith(TC_PREFIX) => Some(content)
        case _ => None
      }
    }
  }

  def createDistributions(nodeMap: Map[Int, TestNode])(distributeSuitePerProcess: TpTestSuite => Boolean)
    : List[TestDistribution] = {

    @tailrec
    def findSuiteParent(node: TestNode): TpTestSuite = {
      nodeMap(node.parentId) match {
        case group: TpTestGroup => findSuiteParent(group)
        case suite: TpTestSuite => suite
        case _ => ???
      }
    }

    val suiteToTestsMap = nodeMap
      .values
      .collect { case test: TpTest => test }
      .foldLeft(Map.empty[TpTestSuite, NonEmptyList[TpTest]]) { (map, test) =>
        val suite = findSuiteParent(test)
        map.updated(
          suite,
          map.get(suite) match {
            case Some(list) => test :: list
            case None => NonEmptyList.one(test)
          }
        )
      }

    suiteToTestsMap.toList.flatMap { case (suite, tests) =>
      if (distributeSuitePerProcess(suite)) {
        SuitePerProcessDistribution(suite, tests.sortBy(_.id)) :: Nil
      }
      else {
        tests.toList.map(TestPerProcessDistribution)
      }
    }
  }

  def duplicateNodeMap(
    nodeMap: Map[Int, TestNode],
    idOffset: Int,
    duplicateSeq: Int
  ): Map[Int, TestNode] = {
    nodeMap
      .map { case (id, node) =>
        val newId = id + idOffset
        val newNode = node match {
          case test: TpTest =>
            test.copy(id = newId, parentId = test.parentId + idOffset)
          case group: TpTestGroup =>
            group.copy(id = newId, parentId = group.parentId + idOffset)
          case suite: TpTestSuite =>
            suite.copy(id = newId, duplicateSeq = duplicateSeq)
        }

        newId -> newNode
      }
  }
}
