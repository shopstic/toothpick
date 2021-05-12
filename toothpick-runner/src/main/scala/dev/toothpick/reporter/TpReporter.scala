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
  sealed trait TestOutcome {
    def isFailure: Boolean = false
  }
  case object TestPassed extends TestOutcome
  case object TestIgnored extends TestOutcome
  final case class TestFailed(message: String, details: String) extends TestOutcome {
    override val isFailure: Boolean = true
  }

  sealed trait TpReporterEvent {
    def nodeId: Int
  }
  final case class TestReport(
    nodeId: Int,
    outcome: TestOutcome,
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
      reportedOutcomeRef <- Ref.make(Option.empty[TestOutcome])
      zlogger <- IzLogging.zioLogger
      testReportsStream <- watchTestReports(TpRunTestId(uuid, test.id))
      startMsRef <- Ref.make(Instant.now)
      lineBuffer <- zio.Queue.unbounded[String]
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
            case nonEmptyEvent: TpTestEvent.NonEmpty =>
              nonEmptyEvent match {
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
                          reportedOutcomeRef
                            .set(Some(TestFailed(
                              message = unescape(sm.attributes.getOrElse(Attrs.MESSAGE, "")),
                              details = unescape(sm.attributes.getOrElse(Attrs.DETAILS, ""))
                            ))) *> noop

                        case Names.TEST_IGNORED =>
                          reportedOutcomeRef
                            .set(Some(TestIgnored)) *> noop

                        case Names.MESSAGE if sm.attributes.get(Attrs.STATUS).contains("ERROR") =>
                          reportedOutcomeRef
                            .set(Some(TestFailed(
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

                case TpTestOutputLinePart(content, pipe, isLast) =>
                  if (isLast) {
                    lineBuffer.takeAll.flatMap { parts =>
                      val concatenated = parts.mkString("", "", content)
                      output(TestOutputLine(test.id, pipe, concatenated))
                    }
                  }
                  else {
                    lineBuffer.offer(content) *> noop
                  }

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
                    maybeReportedOutcome <- reportedOutcomeRef
                      .get
                      .map {
                        _.orElse(Option.when(exitCode != 0) {
                          TestFailed(
                            message = "",
                            details = ""
                          )
                        })
                      }
                    flushed <- if (maybeReportedOutcome.exists(_.isFailure)) outputBuffer.takeAll else noop
                  } yield {
                    events ++ flushed :+ TestReport(
                      nodeId = test.id,
                      outcome = maybeReportedOutcome.getOrElse(TestPassed),
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
                    Chunk.fromIterable(flushed) :+ TestReport(
                      nodeId = test.id,
                      outcome = TestFailed(s"Worker failed unexpectedly", message),
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
                    Chunk.fromIterable(flushed) :+ TestReport(
                      nodeId = test.id,
                      outcome = TestFailed(s"Test was aborted", ""),
                      startTime = startTime,
                      endTime = report.time,
                      isLast = true
                    )
                  }
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
      lastTestReportPromise <- zio.Promise.make[Nothing, TestReport]
      testReportsStream <- watchTestReports(TpRunTestId(uuid, suite.id))
      hasFailedTestsRef <- Ref.make[Boolean](false)
      lineBuffer <- zio.Queue.unbounded[String]
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

      def reportNext(maybeTestName: Option[String], outcome: TestOutcome, time: Instant) = {
        pendingTests.modify {
          case test :: tail =>
            for {
              startTime <- startTimeRef.getAndSet(time)
              testOutcome = TestReport(
                nodeId = test.id,
                outcome = outcome,
                startTime = startTime,
                endTime = time,
                isLast = tail.isEmpty
              )
              _ <- hasFailedTestsRef.update(yes => yes || outcome.isFailure)
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

      def reportAllRemaining(outcome: TestOutcome, time: Instant) = {
        pendingTests
          .get
          .flatMap { tests =>
            ZIO.foldLeft(tests)(Chunk[TpReporterEvent]()) { (accum, _) =>
              reportNext(None, outcome, time)
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
                        TestPassed,
                        time
                      )

                    case Names.TEST_FAILED =>
                      reportNext(
                        sm.attributes.get(Attrs.NAME),
                        TestFailed(
                          message = unescape(sm.attributes.getOrElse(Names.MESSAGE, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.DETAILS, ""))
                        ),
                        time
                      )

                    case Names.TEST_IGNORED =>
                      reportNext(
                        sm.attributes.get(Attrs.NAME),
                        TestIgnored,
                        time
                      )

                    case Names.MESSAGE if sm.attributes.get(Attrs.STATUS).contains("ERROR") =>
                      reportNext(
                        sm.attributes.get(Attrs.NAME),
                        TestFailed(
                          message = unescape(sm.attributes.getOrElse(Attrs.TEXT, "")),
                          details = unescape(sm.attributes.getOrElse(Attrs.ERROR_DETAILS, ""))
                        ),
                        time
                      )

                    case _ => noop
                  }

                case Left(reason) =>
                  zlogger.warn(s"Failed parsing service message $content $reason") *> noop
              }

            case TpTestOutputLine(content, pipe) =>
              output(TestOutputLine(suite.id, pipe, content))

            case TpTestOutputLinePart(content, pipe, isLast) =>
              if (isLast) {
                lineBuffer.takeAll.flatMap { parts =>
                  val concatenated = parts.mkString("", "", content)
                  output(TestOutputLine(suite.id, pipe, concatenated))
                }
              }
              else {
                lineBuffer.offer(content) *> noop
              }

            case TpTestResult(exitCode) =>
              for {
                reports <- reportAllRemaining(
                  TestFailed(
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
                  TestFailed(
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
                  TestFailed(
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
