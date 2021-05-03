package dev.toothpick.runner

import cats.data.NonEmptyList
import dev.toothpick.proto.api._
import dev.toothpick.runner.intellij.TpIntellijServiceMessageParser.TC_PREFIX
import dev.toothpick.runner.intellij.TpIntellijServiceMessageReporter.ReportTestFailure

import scala.annotation.tailrec
import scala.util.matching.Regex

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
    failure: Option[ReportTestFailure],
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

  def createDistributions(
    hierarchy: Map[Int, TestNode],
    testPerProcessFileNameRegex: Regex
  ): List[TestDistribution] = {

    @tailrec
    def findSuiteParent(node: TestNode): TpTestSuite = {
      hierarchy(node.parentId) match {
        case group: TpTestGroup => findSuiteParent(group)
        case suite: TpTestSuite => suite
        case _ => ???
      }
    }

    val suiteToTestsMap = hierarchy
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
      if (testPerProcessFileNameRegex.matches(suite.name)) {
        tests.toList.map(TestPerProcessDistribution)
      }
      else {
        SuitePerProcessDistribution(suite, tests.sortBy(_.id)) :: Nil
      }
    }
  }

  def duplicateHierarchy(
    hierarchy: Map[Int, TestNode],
    idOffset: Int,
    duplicateSeq: Int
  ): Map[Int, TestNode] = {
    hierarchy
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
