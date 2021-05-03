package dev.toothpick.runner.intellij

import cats.data.NonEmptyList
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.app.TpRunnerApp.{MatchesEmptyLineStdoutReport, MatchesTeamCityServiceMessageEvent}
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api._
import dev.toothpick.runner.intellij.TpIntellijServiceMessageRenderer.{render, unescape}
import dev.toothpick.runner.intellij.TpIntellijServiceMessages.{Attrs, Names}
import io.grpc.StatusRuntimeException
import zio.clock.Clock
import zio.console.{putStrLn, Console}
import zio.stream.ZStream
import zio.{Chunk, RIO, Ref, URIO, ZIO, ZRefM}

import java.time.Instant
import java.util.UUID

object TpIntellijServiceMessageReporter {

  final case class ReportTestFailure(message: String, details: String)
  final case class ReportQueueItem(id: Int, failure: Option[ReportTestFailure], durationMs: Long)

  // This is to deal with the extra "\n" which the Intellij Scala plugin prepends to every single service message here:
  // https://github.com/JetBrains/intellij-scala/blob/562e90dc4e77fbe2866e13e572c1ade93ee18e25/scala/testRunners/src/org/jetbrains/plugins/scala/testingSupport/scalaTest/TeamcityReporter.java#L11
  private def trimPrecedingNewLine(pair: (Option[TpTestReport], TpTestReport)): Chunk[TpTestReport] = {
    pair match {
      case (_, MatchesEmptyLineStdoutReport()) => Chunk.empty
      case (Some(MatchesEmptyLineStdoutReport()), r2 @ TpTestReport(_, MatchesTeamCityServiceMessageEvent(_))) =>
        Chunk.single(r2)
      case (Some(r1 @ MatchesEmptyLineStdoutReport()), r2) =>
        Chunk(r1, r2)
      case (_, r2) => Chunk.single(r2)
    }
  }

  private def renderTestErr(id: String, message: String): String = {
    render(
      Names.TEST_STD_ERR,
      Map(
        Attrs.NODE_ID -> id,
        Attrs.OUT -> s"$message\n"
      )
    )
  }

  private def watchTestReports(runTestId: TpRunTestId)
    : URIO[TpApiClient, ZStream[Any, StatusRuntimeException, TpTestReport]] = {
    for {
      lastRef <- Ref.make[Option[TpWatchResponse]](None)
      apiClient <- ZIO.access[TpApiClient](_.get)
    } yield {
      zio.stream
        .Stream
        .fromEffect {
          lastRef
            .get
            .map {
              case Some(last) if last.ended => zio.stream.Stream.empty
              case maybeLast =>
                apiClient
                  .watch(TpWatchRequest(runTestId, maybeLast.map(_.versionstamp)))
                  .mapError(_.asRuntimeException())
                  .tap(r => lastRef.set(Some(r)))
                  .map(_.report)
            }
        }
        .flatten
        /*.retry {
          Schedule
            .identity[Throwable]
            .tapOutput(e => zlogger.error(s"Watch stream failed, will retry $e"))
        }*/
        .zipWithPrevious
        .map(trimPrecedingNewLine)
        .flattenChunks
    }
  }

  def reportSuite(
    uuid: UUID,
    suite: TpTestSuite,
    tests: NonEmptyList[TpTest],
    reportQueue: zio.Queue[ReportQueueItem],
    onlyLogIfFailed: Boolean
  ): RIO[Console with Clock with TpApiClient with IzLogging, Unit] = {
    for {
      zlogger <- IzLogging.zioLogger
      consoleBuffer <- zio.Queue.unbounded[String]
      logToConsole = if (onlyLogIfFailed) consoleBuffer.offer(_: String).unit else putStrLn(_: String)

      pendingTests <- ZRefM.make[List[TpTest]](tests.toList)
      startInstantRef <- zio.clock.instant.flatMap(Ref.make)
      lastTestReportPromise <- zio.Promise.make[Nothing, ReportQueueItem]
      hasFailedTestsRef <- Ref.make(false)

      reportNext =
        (maybeTestName: Option[String], maybeFailure: Option[ReportTestFailure], time: Instant) => {
          pendingTests.update {
            case test :: tail =>
              for {
                _ <- hasFailedTestsRef.set(true).when(maybeFailure.nonEmpty)
                startInstant <- startInstantRef.getAndSet(time)
                reporQueueItem = ReportQueueItem(
                  id = test.id,
                  failure = maybeFailure,
                  durationMs = math.max(0, time.toEpochMilli - startInstant.toEpochMilli)
                )
                _ <- maybeTestName match {
                  case Some(reportedName) if reportedName != test.name =>
                    ZIO.fail(new IllegalStateException(
                      s"Reported test name doesn't match: '$reportedName' vs '${test.name}'"
                    ))
                  case _ =>
                    ZIO.unit
                }
                _ <-
                  if (tail.isEmpty) {
                    lastTestReportPromise.succeed(reporQueueItem)
                  }
                  else {
                    reportQueue.offer(reporQueueItem)
                  }
              } yield tail

            case Nil => ZIO.fail(new IllegalStateException("Received extra test report"))
          }
        }

      reportAllRemaining = (failure: ReportTestFailure, time: Instant) => {
        pendingTests
          .get
          .flatMap { tests =>
            ZIO.foreach(tests) { _ =>
              reportNext(None, Some(failure), time)
            }
          }
      }

      testReportsStream <- watchTestReports(TpRunTestId(uuid, suite.id))
      suiteStringId = suite.id.toString
      renderErr = renderTestErr(suiteStringId, _)
      _ <- testReportsStream
        .foreach { report: TpTestReport =>
          val time = report.time
          val task: RIO[Console, Unit] = report.event match {
            case TpTestStarted(workerId, workerNode) =>
              startInstantRef.set(time) *>
                zlogger.info(s"Suite assigned to $workerNode $workerId ${suite.name}")

            case TpImagePullingStarted(workerNode, image) =>
              zlogger.info(s"$workerNode is pulling $image")

            case MatchesTeamCityServiceMessageEvent(content) =>
              TpIntellijServiceMessageParser.parse(content) match {
                case Right(sm) =>
                  sm.name match {
                    case Names.TEST_FINISHED =>
                      reportNext(
                        sm.attributes.get(Attrs.NAME).map(unescape),
                        None,
                        time
                      )

                    case Names.TEST_FAILED =>
                      reportNext(
                        sm.attributes.get(Attrs.NAME),
                        Some(ReportTestFailure(
                          message = unescape(sm.attributes.getOrElse(Names.MESSAGE, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.DETAILS, ""))
                        )),
                        time
                      )

                    case Names.MESSAGE if sm.attributes.get(Attrs.STATUS).contains("ERROR") =>
                      reportNext(
                        sm.attributes.get(Attrs.NAME),
                        Some(ReportTestFailure(
                          message = unescape(sm.attributes.getOrElse(Attrs.TEXT, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.ERROR_DETAILS, ""))
                        )),
                        time
                      )

                    case _ => ZIO.unit
                  }

                case Left(reason) =>
                  zlogger.warn(s"Failed parsing service message $content $reason")
              }

            case TpTestOutputLine(content, pipe) =>
              logToConsole(render(
                if (pipe.isStdout) Names.TEST_STD_OUT else Names.TEST_STD_ERR,
                Map(
                  Attrs.NODE_ID -> suiteStringId,
                  Attrs.OUT -> (content + "\n")
                )
              ))

            case TpTestResult(exitCode) =>
              for {
                _ <- exitCode match {
                  case 124 =>
                    logToConsole(renderErr("Test run process took too long and was interrupted with SIGTERM"))
                  case 137 =>
                    logToConsole(renderErr("Test run process received SIGKILL"))
                  case _ =>
                    ZIO.unit
                }
                _ <- reportAllRemaining(
                  ReportTestFailure(
                    message = "Runner process completed prematurely",
                    details = ""
                  ),
                  time
                )
                hasFailedTests <- hasFailedTestsRef.get
                _ <- consoleBuffer
                  .takeAll
                  .flatMap(items => ZIO.foreach(items)(putStrLn(_)))
                  .when(hasFailedTests || exitCode != 0)
                _ <- consoleBuffer.shutdown
                lastTestReport <- lastTestReportPromise.await
                _ <- reportQueue.offer(lastTestReport)
              } yield ()

            case TpTestException(message) =>
              for {
                _ <- reportAllRemaining(
                  ReportTestFailure(
                    message = "Worker failed unexpectedly",
                    details = message
                  ),
                  time
                )
                _ <- consoleBuffer
                  .takeAll
                  .flatMap(items => ZIO.foreach(items)(putStrLn(_)))
                _ <- consoleBuffer.shutdown
                lastTestReport <- lastTestReportPromise.await
                _ <- reportQueue.offer(lastTestReport)
              } yield ()

            case _: TpTestAborted =>
              ZIO.fail(new IllegalStateException("Test was aborted"))

            case TpTestEvent.Empty =>
              ZIO.unit
          }

          task
        }
    } yield ()
  }

  def reportTest(
    uuid: UUID,
    test: TpTest,
    reportQueue: zio.Queue[ReportQueueItem],
    onlyLogIfFailed: Boolean
  ): RIO[Console with TpApiClient with IzLogging, Unit] = {
    for {
      reportedFailureRef <- Ref.make(Option.empty[ReportTestFailure])
      consoleBuffer <- zio.Queue.unbounded[String]
      logToConsole = if (onlyLogIfFailed) consoleBuffer.offer(_: String).unit else putStrLn(_: String)

      zlogger <- IzLogging.zioLogger
      testReportsStream <- watchTestReports(TpRunTestId(uuid, test.id))
      leafStringId = test.id.toString
      renderErr = renderTestErr(leafStringId, _)
      startMsRef <- Ref.make(Instant.now)
      _ <- testReportsStream
        .foreach { report: TpTestReport =>
          val task: RIO[Console, Unit] = report.event match {
            case TpTestStarted(workerId, workerNode) =>
              startMsRef.set(report.time) *>
                zlogger.info(s"Test assigned to $workerNode $workerId ${test.fullName}")

            case TpImagePullingStarted(workerNode, image) =>
              zlogger.info(s"$workerNode is pulling $image")

            case MatchesTeamCityServiceMessageEvent(content) =>
              /*putStrLn(render(
                Names.TEST_STD_OUT,
                Map(
                  Attrs.NODE_ID -> leafStringId,
                  Attrs.OUT -> (content + "\n")
                )
              )) *> */
              TpIntellijServiceMessageParser.parse(content) match {
                case Right(sm) =>
                  sm.name match {
                    case Names.TEST_FAILED =>
                      reportedFailureRef
                        .set(Some(ReportTestFailure(
                          message = unescape(sm.attributes.getOrElse(Attrs.MESSAGE, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.DETAILS, ""))
                        )))

                    case Names.MESSAGE if sm.attributes.get(Attrs.STATUS).contains("ERROR") =>
                      reportedFailureRef
                        .set(Some(ReportTestFailure(
                          message = unescape(sm.attributes.getOrElse(Attrs.TEXT, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.ERROR_DETAILS, ""))
                        )))

                    case _ =>
                      ZIO.unit
                  }

                case Left(reason) =>
                  zlogger.warn(s"Failed parsing service message $content $reason")
              }

            case TpTestOutputLine(content, pipe) =>
              logToConsole(render(
                if (pipe.isStdout) Names.TEST_STD_OUT else Names.TEST_STD_ERR,
                Map(
                  Attrs.NODE_ID -> leafStringId,
                  Attrs.OUT -> (content + "\n")
                )
              ))

            case TpTestResult(exitCode) =>
              for {
                startTime <- startMsRef.get
                elapsed = math.max(0, report.time.toEpochMilli - startTime.toEpochMilli)
                _ <- exitCode match {
                  case 124 =>
                    logToConsole(renderErr("Test run process took too long and was interrupted with SIGTERM"))
                  case 137 =>
                    logToConsole(renderErr("Test run process received SIGKILL"))
                  case _ =>
                    ZIO.unit
                }
                maybeReportedFailure <- reportedFailureRef
                  .get
                  .map {
                    _.orElse(Option.when(exitCode != 0) {
                      ReportTestFailure(
                        message = "",
                        details = ""
                      )
                    })
                  }
                _ <- consoleBuffer
                  .takeAll
                  .flatMap(items => ZIO.foreach(items)(putStrLn(_)))
                  .when(maybeReportedFailure.nonEmpty)
                _ <- consoleBuffer.shutdown
                _ <- reportQueue.offer(ReportQueueItem(
                  id = test.id,
                  failure = maybeReportedFailure,
                  durationMs = elapsed
                ))
              } yield ()

            case TpTestException(message) =>
              for {
                startTime <- startMsRef.get
                elapsed = math.max(0, report.time.toEpochMilli - startTime.toEpochMilli)
                _ <- consoleBuffer
                  .takeAll
                  .flatMap(items => ZIO.foreach(items)(putStrLn(_)))
                _ <- consoleBuffer.shutdown
                _ <- reportQueue.offer(ReportQueueItem(
                  id = test.id,
                  Some(ReportTestFailure(s"Worker failed unexpectedly", message)),
                  durationMs = elapsed
                ))
              } yield ()

            case _: TpTestAborted =>
              ZIO.fail(new IllegalStateException("Test was aborted"))

            case TpTestEvent.Empty =>
              ZIO.unit
          }

          task
        }
    } yield ()
  }
}
