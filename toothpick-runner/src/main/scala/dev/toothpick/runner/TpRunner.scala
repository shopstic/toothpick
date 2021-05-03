package dev.toothpick.runner

import dev.chopsticks.fp.zio_ext.{MeasuredLogging, ZIOExtensions}
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api.{TpRunRequest, TpTestRunOptions}
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
import zio.{RIO, UIO}

import java.util.UUID
import scala.util.matching.Regex

object TpRunner {
  final case class TpRunnerConfig(
    containerizer: TpRunnerContainerizerConfig,
    duplicateCount: NonNegInt,
    testPerProcessFileNameRegex: Regex
  )

  final case class TpRunnerState(
    runId: UUID,
    hierarchy: Map[Int, TestNode],
    distributions: List[TestDistribution]
  )

  def run(context: TpRunnerContext, config: TpRunnerConfig): RIO[TpApiClient with MeasuredLogging, TpRunnerState] = {
    for {
      containerImageFib <- TpRunnerContainerizer.containerize(context, config.containerizer)
        .logResult("containerize", identity)
        .fork

      startingNodeId = 2
      hierachy <- TpScalaTestDiscovery.discover(context, startingNodeId)
        .log("discoverScalaTestSuites")

      effectiveHierarchy <- UIO {
        if (config.duplicateCount.value == 0) hierachy
        else {
          (0 to config.duplicateCount.value).foldLeft(Map.empty[Int, TestNode]) { (accum, seq) =>
            accum ++ TpRunnerUtils.duplicateHierarchy(hierachy, seq * hierachy.size + startingNodeId, seq + 1)
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

      distributions = TpRunnerUtils.createDistributions(effectiveHierarchy, config.testPerProcessFileNameRegex)

      runResponse <- TpApiClient
        .run(TpRunRequest(
          effectiveHierarchy,
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
            .toMap
        ))
        .mapError(_.asRuntimeException())
        .log("Send run request to Toothpick Server")

    } yield {
      TpRunnerState(runId = runResponse.runId, hierarchy = hierachy, distributions = distributions)
    }
  }

}
