package dev.toothpick.reporter

import cats.data.NonEmptyList
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.proto.api.TpTestOutputLine.Pipe
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api._
import dev.toothpick.runner.TpRunnerUtils.{MatchesEmptyLineStdoutReport, MatchesTeamCityServiceMessageEvent}
import dev.toothpick.runner.intellij.TpIntellijServiceMessageParser
import dev.toothpick.runner.intellij.TpIntellijServiceMessageRenderer.unescape
import dev.toothpick.runner.intellij.TpIntellijServiceMessages.{Attrs, Names}
import io.grpc.StatusRuntimeException
import zio.clock.Clock
import zio.stream.ZStream
import zio.{Chunk, Ref, UIO, URIO, ZIO, ZRefM}

import java.time.Instant
import java.util.UUID

object TpReporter {
  final case class TestFailureDetails(message: String, details: String)

  sealed trait TpReporterEvent {
    def nodeId: Int
  }
  final case class TestOutcome(
    nodeId: Int,
    failure: Option[TestFailureDetails],
    startTime: Instant,
    endTime: Instant,
    isLast: Boolean
  ) extends TpReporterEvent
  final case class TestOutputLine(nodeId: Int, pipe: TpTestOutputLine.Pipe, content: String) extends TpReporterEvent

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

  def reportTest(
    uuid: UUID,
    test: TpTest,
    onlyLogIfFailed: Boolean
  ): URIO[TpApiClient with IzLogging, ZStream[Any, StatusRuntimeException, TpReporterEvent]] = {
    for {
      reportedFailureRef <- Ref.make(Option.empty[TestFailureDetails])
      zlogger <- IzLogging.zioLogger
      testReportsStream <- watchTestReports(TpRunTestId(uuid, test.id))
      startMsRef <- Ref.make(Instant.now)
      outputBuffer <- zio.Queue.unbounded[TestOutputLine]
    } yield {
      val noop: UIO[Chunk[TpReporterEvent]] = ZIO.succeed(Chunk.empty)

      def output(line: TestOutputLine): UIO[Chunk[TestOutputLine]] = {
        if (onlyLogIfFailed) {
          outputBuffer.offer(line).as(Chunk.empty)
        }
        else {
          ZIO.succeed(Chunk.single(line))
        }
      }

      testReportsStream
        .mapM { report =>
          val task: UIO[Chunk[TpReporterEvent]] = report.event match {
            case TpTestStarted(workerId, workerNode) =>
              for {
                _ <- startMsRef.set(report.time)
                _ <- zlogger.info(s"Test assigned to $workerNode $workerId ${test.fullName}")
              } yield Chunk.empty

            case TpImagePullingStarted(workerNode, image) =>
              zlogger.info(s"$workerNode is pulling $image") *> noop

            case MatchesTeamCityServiceMessageEvent(content) =>
              TpIntellijServiceMessageParser.parse(content) match {
                case Right(sm) =>
                  sm.name match {
                    case Names.TEST_FAILED =>
                      reportedFailureRef
                        .set(Some(TestFailureDetails(
                          message = unescape(sm.attributes.getOrElse(Attrs.MESSAGE, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.DETAILS, ""))
                        ))) *> noop

                    case Names.MESSAGE if sm.attributes.get(Attrs.STATUS).contains("ERROR") =>
                      reportedFailureRef
                        .set(Some(TestFailureDetails(
                          message = unescape(sm.attributes.getOrElse(Attrs.TEXT, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.ERROR_DETAILS, ""))
                        ))) *> noop

                    case _ =>
                      noop
                  }

                case Left(reason) =>
                  zlogger.warn(s"Failed parsing service message $content $reason") *> noop
              }

            case TpTestOutputLine(content, pipe) =>
              output(TestOutputLine(test.id, pipe, content))

            case TpTestResult(exitCode) =>
              for {
                startTime <- startMsRef.get
                events <- exitCode match {
                  case 124 =>
                    output(TestOutputLine(
                      test.id,
                      Pipe.STDERR,
                      "Test run process took too long and was interrupted with SIGTERM"
                    ))
                  case 137 =>
                    output(TestOutputLine(test.id, Pipe.STDERR, "Test run process received SIGKILL"))
                  case _ =>
                    noop
                }
                maybeReportedFailure <- reportedFailureRef
                  .get
                  .map {
                    _.orElse(Option.when(exitCode != 0) {
                      TestFailureDetails(
                        message = "",
                        details = ""
                      )
                    })
                  }
                flushed <- if (maybeReportedFailure.nonEmpty) outputBuffer.takeAll else noop
              } yield {
                events ++ flushed :+ TestOutcome(
                  nodeId = test.id,
                  failure = maybeReportedFailure,
                  startTime = startTime,
                  endTime = report.time,
                  isLast = true
                )
              }

            case TpTestException(message) =>
              for {
                startTime <- startMsRef.get
                flushed <- outputBuffer.takeAll
              } yield {
                Chunk.fromIterable(flushed) :+ TestOutcome(
                  nodeId = test.id,
                  failure = Some(TestFailureDetails(s"Worker failed unexpectedly", message)),
                  startTime = startTime,
                  endTime = report.time,
                  isLast = true
                )
              }

            case _: TpTestAborted =>
              for {
                startTime <- startMsRef.get
                flushed <- outputBuffer.takeAll
              } yield {
                Chunk.fromIterable(flushed) :+ TestOutcome(
                  nodeId = test.id,
                  failure = Some(TestFailureDetails(s"Test was aborted", "")),
                  startTime = startTime,
                  endTime = report.time,
                  isLast = true
                )
              }

            case TpTestEvent.Empty =>
              noop
          }

          task
        }
        .flattenChunks
    }
  }

  def reportSuite(
    uuid: UUID,
    suite: TpTestSuite,
    tests: NonEmptyList[TpTest],
    onlyLogIfFailed: Boolean
  ): URIO[TpApiClient with Clock with IzLogging, ZStream[Any, RuntimeException, TpReporterEvent]] = {
    val noop: UIO[Chunk[TpReporterEvent]] = ZIO.succeed(Chunk.empty)

    for {
      zlogger <- IzLogging.zioLogger
      pendingTests <- ZRefM.make[List[TpTest]](tests.toList)
      startTimeRef <- zio.clock.instant.flatMap(Ref.make)
      lastTestReportPromise <- zio.Promise.make[Nothing, TestOutcome]
      testReportsStream <- watchTestReports(TpRunTestId(uuid, suite.id))
      hasFailedTestsRef <- Ref.make[Boolean](false)
      outputBuffer <- zio.Queue.unbounded[TestOutputLine]
    } yield {
      def output(line: TestOutputLine): UIO[Chunk[TestOutputLine]] = {
        if (onlyLogIfFailed) {
          outputBuffer.offer(line).as(Chunk.empty)
        }
        else {
          ZIO.succeed(Chunk.single(line))
        }
      }

      def reportNext(maybeTestName: Option[String], maybeFailure: Option[TestFailureDetails], time: Instant) = {
        pendingTests.modify {
          case test :: tail =>
            for {
              startTime <- startTimeRef.getAndSet(time)
              testOutcome = TestOutcome(
                nodeId = test.id,
                failure = maybeFailure,
                startTime = startTime,
                endTime = time,
                isLast = tail.isEmpty
              )
              _ <- hasFailedTestsRef.update(yes => yes || maybeFailure.nonEmpty)
              _ <- maybeTestName match {
                case Some(reportedName) if reportedName != test.name =>
                  ZIO.fail(new IllegalStateException(
                    s"Reported test name doesn't match: '$reportedName' vs '${test.name}'"
                  ))
                case _ =>
                  noop
              }
              out <-
                if (testOutcome.isLast) {
                  lastTestReportPromise.succeed(testOutcome).as(Chunk.empty)
                }
                else {
                  ZIO.succeed(Chunk.single(testOutcome))
                }
            } yield {
              out -> tail
            }

          case Nil => ZIO.fail(new IllegalStateException("Received extra test report"))
        }
      }

      def reportAllRemaining(failure: TestFailureDetails, time: Instant) = {
        pendingTests
          .get
          .flatMap { tests =>
            ZIO.foldLeft(tests)(Chunk[TpReporterEvent]()) { (accum, _) =>
              reportNext(None, Some(failure), time)
                .map(chunk => accum ++ chunk)
            }
          }
      }

      testReportsStream
        .mapM { report: TpTestReport =>
          val time = report.time

          val task = report.event match {
            case TpTestStarted(workerId, workerNode) =>
              for {
                _ <- startTimeRef.set(report.time)
                _ <- zlogger.info(s"Suite assigned to $workerNode $workerId ${suite.name}")
              } yield Chunk.empty

            case TpImagePullingStarted(workerNode, image) =>
              zlogger.info(s"$workerNode is pulling $image") *> noop

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
                        Some(TestFailureDetails(
                          message = unescape(sm.attributes.getOrElse(Names.MESSAGE, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.DETAILS, ""))
                        )),
                        time
                      )

                    case Names.MESSAGE if sm.attributes.get(Attrs.STATUS).contains("ERROR") =>
                      reportNext(
                        sm.attributes.get(Attrs.NAME),
                        Some(TestFailureDetails(
                          message = unescape(sm.attributes.getOrElse(Attrs.TEXT, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.ERROR_DETAILS, ""))
                        )),
                        time
                      )

                    case _ => noop
                  }

                case Left(reason) =>
                  zlogger.warn(s"Failed parsing service message $content $reason") *> noop
              }

            case TpTestOutputLine(content, pipe) =>
              output(TestOutputLine(suite.id, pipe, content))

            case TpTestResult(exitCode) =>
              for {
                reports <- reportAllRemaining(
                  TestFailureDetails(
                    message = "Runner process completed prematurely",
                    details = ""
                  ),
                  time
                )
                lastTestReport <- lastTestReportPromise.await
                events <- exitCode match {
                  case 124 =>
                    output(TestOutputLine(
                      suite.id,
                      Pipe.STDERR,
                      "Suite run process took too long and was interrupted with SIGTERM"
                    ))
                  case 137 =>
                    output(TestOutputLine(suite.id, Pipe.STDERR, "Suite run process received SIGKILL"))
                  case _ =>
                    noop
                }
                hasFailedTests <- hasFailedTestsRef.get
                flushed <- if (hasFailedTests) outputBuffer.takeAll else noop
              } yield {
                events ++ flushed ++ reports :+ lastTestReport
              }

            case TpTestException(message) =>
              for {
                reports <- reportAllRemaining(
                  TestFailureDetails(
                    message = "Worker failed unexpectedly",
                    details = message
                  ),
                  time
                )
                lastTestReport <- lastTestReportPromise.await
                flushed <- outputBuffer.takeAll
              } yield {
                Chunk.fromIterable(flushed) ++ reports :+ lastTestReport
              }

            case _: TpTestAborted =>
              for {
                reports <- reportAllRemaining(
                  TestFailureDetails(
                    message = "Suite was aborted",
                    details = ""
                  ),
                  time
                )
                lastTestReport <- lastTestReportPromise.await
                flushed <- outputBuffer.takeAll
              } yield {
                Chunk.fromIterable(flushed) ++ reports :+ lastTestReport
              }

            case TpTestEvent.Empty =>
              noop
          }

          task
        }
        .flattenChunks
    }
  }
}
