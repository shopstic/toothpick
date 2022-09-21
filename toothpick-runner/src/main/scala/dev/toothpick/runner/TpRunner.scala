package dev.toothpick.runner

import com.google.protobuf.ByteString
import dev.chopsticks.fp.zio_ext.{MeasuredLogging, ZIOExtensions}
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api._
import dev.toothpick.runner.TpRunnerContainerizer.TpRunnerContainerizerConfig
import dev.toothpick.runner.TpRunnerUtils.{
  SuitePerProcessDistribution,
  TestDistribution,
  TestNode,
  TestPerProcessDistribution
}
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser.{TpRunnerContext, TpScalaTestContext, TpZTestContext}
import dev.toothpick.runner.scalatest.TpScalaTestDiscovery
import eu.timepit.refined.types.numeric.NonNegInt
import eu.timepit.refined.types.string.NonEmptyString
import io.grpc.StatusRuntimeException
import wvlet.airframe.ulid.ULID
import zio.blocking.Blocking
import zio.{RIO, Task, UIO, ZIO}

import scala.util.matching.Regex

object TpRunner {
  final case class TpRunnerConfig(
    containerizer: TpRunnerContainerizerConfig,
    duplicateCount: NonNegInt,
    testPerProcessFileNameRegex: Regex,
    seedArtifactArchiveFilePath: Option[NonEmptyString]
  )

  final case class TpRunnerState(
    runId: ULID,
    nodeMap: Map[Int, TestNode],
    distributions: List[TestDistribution]
  )

  def fetchState(runId: ULID): ZIO[TpApiClient with MeasuredLogging, StatusRuntimeException, TpRunnerState] = {

    val task = for {
      nodeMap <- TpApiClient
        .getHierarchy(TpGetHierarchyRequest(runId))
        .fold(Map.empty[Int, TestNode]) { (map, node) =>
          import TpRunnerUtils.TestNodeOps
          node match {
            case nonEmptyNode: TpTestNode.NonEmpty =>
              map.updated(nonEmptyNode.id, nonEmptyNode)
            case TpTestNode.Empty =>
              map
          }
        }
        .log("fetch nodes")

      suiteIds <- TpApiClient
        .getDistributions(TpGetDistributionsRequest(runId))
        .filter { distribution =>
          nodeMap.get(distribution.testId) match {
            case Some(_: TpTestSuite) => true
            case _ => false
          }
        }
        .map(_.testId)
        .runCollect
        .map(_.toSet)
        .log("fetch distributions")

      distributions = TpRunnerUtils.createDistributions(nodeMap)(suite => suiteIds.contains(suite.id))
    } yield TpRunnerState(runId, nodeMap, distributions)

    task.mapError(_.asRuntimeException())
  }

  def createStage(context: TpRunnerContext, config: TpRunnerConfig): RIO[MeasuredLogging with Blocking, TpRunStage] = {
    for {
      containerImageFib <- TpRunnerContainerizer.containerize(context, config.containerizer)
        .logResult("containerize", identity)
        .fork

      startingNodeId = 2
      nodeMap <- TpScalaTestDiscovery.discover(context, startingNodeId)
        .log("discoverScalaTestSuites")

      effectiveNodeMap <- UIO {
        if (config.duplicateCount.value == 0) nodeMap
        else {
          (0 to config.duplicateCount.value).foldLeft(Map.empty[Int, TestNode]) { (accum, seq) =>
            accum ++ TpRunnerUtils.duplicateNodeMap(nodeMap, seq * nodeMap.size + startingNodeId, seq + 1)
          }
        }
      }

      containerImage <- containerImageFib.join

      nameFilterFlag <- UIO {
        context match {
          case _: TpScalaTestContext => "-testName"
          case _: TpZTestContext => "-t"
        }
      }

      seedArtifactArchiveBytes <- config.seedArtifactArchiveFilePath match {
        case Some(path) => zio.blocking.blocking(Task {
            import better.files._
            ByteString.copyFrom(File(path.value).byteArray)
          })
        case None => ZIO.succeed(ByteString.EMPTY)
      }

      distributions = TpRunnerUtils.createDistributions(effectiveNodeMap) { suite =>
        !config.testPerProcessFileNameRegex.matches(suite.name)
      }
    } yield {
      val suitePerProcessNodeIds = distributions
        .collect { case SuitePerProcessDistribution(suite, _) => suite.id }
        .toSet

      val request = TpRunRequest(
        effectiveNodeMap,
        runOptions = distributions
          .map {
            case TestPerProcessDistribution(test) =>
              test.id -> TpTestRunOptions(
                image = containerImage,
                args = List("-s", test.className, nameFilterFlag, test.fullName)
              )

            case SuitePerProcessDistribution(suite, tests) =>
              val testFilterArgs =
                if (suite.hasFilters) tests.toList.flatMap(test => Vector(nameFilterFlag, test.fullName))
                else Vector.empty

              suite.id -> TpTestRunOptions(
                image = containerImage,
                args = Vector("-s", suite.name) ++ testFilterArgs
              )
          }
          .toMap,
        seedArtifactArchive = seedArtifactArchiveBytes
      )

      TpRunStage(
        request = request,
        suitePerProcessNodeIds = suitePerProcessNodeIds
      )
    }
  }

  def run(stage: TpRunStage): RIO[TpApiClient with MeasuredLogging, TpRunnerState] = {
    for {
      runResponse <- TpApiClient.run(stage.request)
        .mapError(_.asRuntimeException())
        .log("Send run request to Toothpick Server")
    } yield {
      val nodeMap = stage.request.hierarchy.collect { case (id, node: TestNode) =>
        id -> node
      }
      val distributions = TpRunnerUtils.createDistributions(nodeMap) { suite =>
        stage.suitePerProcessNodeIds.contains(suite.id)
      }

      TpRunnerState(
        runId = runResponse.runId,
        nodeMap = nodeMap,
        distributions = distributions
      )
    }
  }

}
