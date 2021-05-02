package dev.toothpick.app
import cats.data.NonEmptyList
import dev.chopsticks.fp.config.{HoconConfig, TypedConfig}
import dev.chopsticks.fp.iz_logging.{IzLogTemplates, IzLogging, IzLoggingRouter}
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api._
import dev.toothpick.runner.TpRunnerContainerizer.TpRunnerContainerizerConfig
import dev.toothpick.runner.TpRunnerModels.{ROOT_NODE_ID, TestNode}
import dev.toothpick.runner._
import dev.toothpick.runner.intellij.TpIntellijServiceMessageParser.TC_PREFIX
import dev.toothpick.runner.intellij.TpIntellijServiceMessageRenderer.{render, renderStartEvent}
import dev.toothpick.runner.intellij.TpIntellijServiceMessageReporter.{ReportQueueItem, ReportTestFailure}
import dev.toothpick.runner.intellij.TpIntellijServiceMessages.{Attrs, Names}
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser.{TpScalaTestConfig, TpZTestConfig}
import dev.toothpick.runner.intellij.{
  TpIntellijServiceMessageRenderer,
  TpIntellijServiceMessageReporter,
  TpIntellijTestRunArgsParser
}
import dev.toothpick.runner.scalatest.TpScalaTestDiscovery
import eu.timepit.refined.auto._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.numeric.NonNegInt
import eu.timepit.refined.types.string.NonEmptyString
import io.grpc.netty.NettyChannelBuilder
import izumi.logstage.api.logger.{LogRouter, LogSink}
import izumi.logstage.api.rendering.RenderingPolicy
import izumi.logstage.api.routing.ConfigurableLogRouter
import logstage.{ConsoleSink, Log}
import pureconfig.ConfigConvert
import scalapb.zio_grpc.ZManagedChannel
import zio.console.putStrLn
import zio.stream.Stream
import zio.{ExitCode, Task, UIO, URIO, ZIO, ZLayer, ZQueue}

import scala.annotation.tailrec
import scala.util.matching.Regex

object TpRunnerApp extends zio.App {
  val RUNNER_NODE_ID = 1

  sealed trait TestDistribution
  final case class SuitePerProcessDistribution(suite: TpTestSuite, tests: NonEmptyList[TpTest]) extends TestDistribution
  final case class TestPerProcessDistribution(test: TpTest) extends TestDistribution

  final case class ReportStreamItem(
    nodes: List[TestNode],
    allDone: Boolean,
    failure: Option[ReportTestFailure],
    durationMs: Long
  )

  final case class AppConfig(
    serverHost: NonEmptyString,
    serverPort: PortNumber,
    serverUsePlainText: Boolean,
    logOnlyFailed: Boolean,
    duplicateCount: NonNegInt,
    testPerProcessFileNameRegex: Regex,
    containerizer: TpRunnerContainerizerConfig
  )

  object AppConfig {
    //noinspection TypeAnnotation
    implicit lazy val configConvert = {
      import dev.chopsticks.util.config.PureconfigConverters._
      ConfigConvert[AppConfig]
    }
  }

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

  private def createDistributions(
    hierarchy: Map[Int, TestNode],
    testPerProcessFileNameRegex: Regex
  ): List[TestDistribution] = {
    import dev.toothpick.runner.TpRunnerModels.TestNodeOps

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

  private def duplicateHierarchy(
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

  private def app(args: List[String]) = {
    for {
      containerizerConfig <- ZIO.service[TpRunnerContainerizerConfig]
      runnerConfig <- Task(TpIntellijTestRunArgsParser.parse(args))
      appConfig <- TypedConfig.get[AppConfig]

      containerImageFib <- TpRunnerContainerizer.containerize(runnerConfig, containerizerConfig)
        .logResult("containerize", identity)
        .fork
      startingNodeId = 2
      hierachy <- TpScalaTestDiscovery.discover(runnerConfig, startingNodeId)
        .log("discoverScalaTestSuites")

      hierachyWithDups <- UIO {
        if (appConfig.duplicateCount.value == 0) hierachy
        else {
          (0 to appConfig.duplicateCount.value).foldLeft(Map.empty[Int, TestNode]) { (accum, seq) =>
            accum ++ duplicateHierarchy(hierachy, seq * hierachy.size + startingNodeId, seq + 1)
          }
        }
      }

      state <- UIO(TpRunnerState.create(hierachyWithDups))

      reportQueue <- ZQueue.unbounded[ReportQueueItem]
      reportFib <- Stream
        .fromQueue(reportQueue)
        .mapAccum(state) { case (s, ReportQueueItem(id, maybeFailure, durationMs)) =>
          val (newState, toEmit) = TpRunnerState.trimPendingMap(s, id)
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

          ZIO.foreach(lines)(putStrLn(_))
        }
        .fork

      totalTestCount = state.hierachy.values.count {
        case _: TpTest => true
        case _ => false
      }

      startLines = state
        .topDownQueue
        .map(renderStartEvent)
        .prepended(render(
          Names.TEST_COUNT,
          Map(Attrs.COUNT -> totalTestCount.toString)
        ))

      _ <- ZIO.foreach_(startLines)(putStrLn(_))

      containerImage <- containerImageFib.join

      nameFilterFlag <- UIO {
        runnerConfig match {
          case _: TpScalaTestConfig => "-testName"
          case _: TpZTestConfig => "-t"
        }
      }

      distributions = createDistributions(state.hierachy, appConfig.testPerProcessFileNameRegex)

      runResponse <- TpApiClient
        .run(TpRunRequest(
          state.hierachy,
          runOptions = distributions
            .map {
              case TestPerProcessDistribution(test) =>
                test.id -> TpTestRunOptions(
                  image = containerImage,
                  args = List("-s", test.className, nameFilterFlag, test.fullName)
                )

              case SuitePerProcessDistribution(suite, tests) =>
                suite.id -> TpTestRunOptions(
                  image = containerImage,
                  args = Vector("-s", suite.name) ++ tests.toList.flatMap(test => Vector(nameFilterFlag, test.fullName))
                )
            }
            .toMap
        ))
        .mapError(_.asRuntimeException())
        .log("Send run request to Toothpick Server")

      _ <- ZIO.foreachPar_(distributions) {
        case TestPerProcessDistribution(test) =>
          TpIntellijServiceMessageReporter.reportTest(
            uuid = runResponse.runId,
            test = test,
            reportQueue = reportQueue,
            onlyLogIfFailed = appConfig.logOnlyFailed
          )

        case SuitePerProcessDistribution(suite, tests) =>
          TpIntellijServiceMessageReporter.reportSuite(
            uuid = runResponse.runId,
            suite = suite,
            tests = tests,
            reportQueue = reportQueue,
            onlyLogIfFailed = appConfig.logOnlyFailed
          )
        /*.onExit { exit =>
            val logCtx = implicitly[LogCtx].copy(level = Log.Level.Debug)

            TpApiClient
              .abort(TpAbortRequest(runResponse.runId))
              .mapError(_.asRuntimeException())
              .logResult(s"Abort ${runResponse.runId}", _.toProtoString)(logCtx)
              .when(!exit.succeeded)
              .orDie
          }*/
      }
      _ <- reportFib.join
      _ <- putStrLn(render(
        Names.TEST_SUITE_FINISHED,
        Map(
          Attrs.NODE_ID -> RUNNER_NODE_ID.toString
        )
      ))
    } yield ()
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    import zio.magic._

    val apiClientLayer = (for {
      appConfig <- TypedConfig.get[AppConfig].toManaged_
      client <- TpApiClient.managed(
        ZManagedChannel[Any] {
          val builder = NettyChannelBuilder
            .forAddress(appConfig.serverHost, appConfig.serverPort)

          if (appConfig.serverUsePlainText) builder.usePlaintext() else builder
        }
      )
    } yield client).toLayer

    val containerizerConfigLayer = TypedConfig.get[AppConfig].map(_.containerizer).toLayer

    val logRouterLayer = ZLayer.succeed(new IzLoggingRouter.Service {
      override def create(threshold: Log.Level, sinks: Seq[LogSink]): LogRouter = {
        val modifiedSinks = sinks.map { sink: LogSink =>
          sink match {
            case _: ConsoleSink =>
              val renderingPolicy = RenderingPolicy.coloringPolicy(Some(IzLogTemplates.consoleLayout))
              val tcRenderingPolicy = new RenderingPolicy {
                override def render(entry: Log.Entry): String = {
                  TpIntellijServiceMessageRenderer.render(
                    Names.TEST_STD_OUT,
                    Map(
                      Attrs.NODE_ID -> RUNNER_NODE_ID.toString,
                      Attrs.OUT -> s"${renderingPolicy.render(entry)}\n"
                    )
                  )
                }
              }

              ConsoleSink(tcRenderingPolicy)
            case sink => sink
          }
        }

        ConfigurableLogRouter(threshold, modifiedSinks)
      }
    })

    val preStart = putStrLn(renderStartEvent(TpTestSuite(
      name = "Toothpick Runner",
      id = RUNNER_NODE_ID,
      parentId = ROOT_NODE_ID,
      duplicateSeq = 0
    )))

    val start = app(args)
      .as(ExitCode(0))
      .interruptAllChildrenPar
      .injectSome[zio.ZEnv](
        HoconConfig.live(Some(this.getClass)),
        TypedConfig.live[AppConfig](logLevel = Log.Level.Info),
        logRouterLayer,
        IzLogging.live(),
        apiClientLayer,
        containerizerConfigLayer
      )
      .orDie

    preStart *> start
  }
}
