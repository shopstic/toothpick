package dev.toothpick.app
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
import dev.toothpick.runner.intellij.TpIntellijTestRunArgsParser.{TpScalaTestConfig, TpZTestConfig}
import dev.toothpick.runner.intellij.{TpIntellijServiceMessageRenderer, TpIntellijTestRunArgsParser}
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

object TpRunnerApp extends zio.App {
  val RUNNER_NODE_ID = 1

  final case class ReportTestFailure(message: String, details: String)
  final case class ReportQueueItem(id: Int, failure: Option[ReportTestFailure], durationMs: Long)
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
                    "testFailed",
                    Map(
                      "nodeId" -> test.id.toString,
                      "message" -> failure.message, // will not work with Intellij if this property is missing altogether, but empty is fine
                      "details" -> failure.details,
                      "error" -> "true", // error will show as red vs. warning/orange on Intellij
                      "duration" -> durationMs.toString
                    )
                  )

                case None =>
                  render(
                    "testFinished",
                    Map(
                      "nodeId" -> test.id.toString,
                      "duration" -> durationMs.toString
                    )
                  )
              }

            case group: TpTestGroup =>
              render(
                "testSuiteFinished",
                Map(
                  "nodeId" -> group.id.toString
                )
              )

            case suite: TpTestSuite =>
              render(
                "testSuiteFinished",
                Map(
                  "nodeId" -> suite.id.toString
                )
              )
          }

          ZIO.foreach(lines)(putStrLn(_))
        }
        .fork

      leaves = state
        .hierachy.values
        .collect {
          case n: TpTest => n
        }
        .toList

      startLines = state
        .topDownQueue
        .map(renderStartEvent)
        .prepended(render(
          "testCount",
          Map("count" -> leaves.size.toString)
        ))

      _ <- ZIO.foreach_(startLines)(putStrLn(_))

      containerImage <- containerImageFib.join

      nameFilterFlag <- UIO {
        runnerConfig match {
          case _: TpScalaTestConfig => "-testName"
          case _: TpZTestConfig => "-t"
        }
      }

      runResponse <- TpApiClient
        .run(TpRunRequest(
          state.hierachy,
          runOptions = leaves
            .map { test =>
              test.id -> TpTestRunOptions(
                image = containerImage,
                args = List(
                  "-s",
                  test.className,
                  nameFilterFlag,
                  test.fullName
                )
              )
            }
            .toMap
        ))
        .mapError(_.asRuntimeException())
        .log("Send run request to Toothpick Server")

      _ <- ZIO.foreachPar_(leaves) { test =>
        TpRunnerIntellijReporter.reportTest(
          uuid = runResponse.runId,
          test = test,
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
        "testSuiteFinished",
        Map(
          "nodeId" -> RUNNER_NODE_ID.toString
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
                    "testStdOut",
                    Map(
                      "nodeId" -> RUNNER_NODE_ID.toString,
                      "out" -> s"${renderingPolicy.render(entry)}\n"
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
