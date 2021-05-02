package dev.toothpick.runner

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.toothpick.app.TpRunnerApp.{
  MatchesEmptyLineStdoutReport,
  MatchesTeamCityServiceMessageEvent,
  ReportQueueItem,
  ReportTestFailure
}
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api._
import dev.toothpick.runner.intellij.TpIntellijServiceMessageParser
import dev.toothpick.runner.intellij.TpIntellijServiceMessageRenderer.{render, unescape}
import zio.console.{putStrLn, Console}
import zio.{Chunk, RIO, Ref, UIO, ZIO}

import java.util.UUID

object TpRunnerIntellijReporter {
  def reportSuite(
    uuid: UUID,
    suite: TpTestSuite,
    tests: List[TpTest],
    reportQueue: zio.Queue[ReportQueueItem],
    onlyLogIfFailed: Boolean
  ) = {}

  def reportTest(
    uuid: UUID,
    test: TpTest,
    reportQueue: zio.Queue[ReportQueueItem],
    onlyLogIfFailed: Boolean
  ): RIO[Console with TpApiClient with IzLogging, Unit] = {
    val leafStringId = test.id.toString

    def renderErr(message: String): String = {
      render(
        "testStdErr",
        Map(
          "nodeId" -> leafStringId,
          "out" -> s"$message\n"
        )
      )
    }

    for {
      zlogger <- IzLogging.zioLogger
      startMsRef <- Ref.make(0L)
      reportedFailureRef <- Ref.make(Option.empty[ReportTestFailure])
      lastRef <- Ref.make[Option[TpWatchResponse]](None)
      consoleBuffer <- zio.Queue.unbounded[String]
      logToConsole <- UIO {
        if (onlyLogIfFailed) consoleBuffer.offer(_: String).unit else putStrLn(_: String)
      }
      _ <- zio.stream
        .Stream
        .fromEffect {
          lastRef
            .get
            .map {
              case Some(last) if last.ended => zio.stream.Stream.empty
              case maybeLast =>
                TpApiClient
                  .watch(TpWatchRequest(TpRunTestId(uuid, test.id), maybeLast.map(_.versionstamp)))
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
        // This is to deal with the extra "\n" which the Intellij Scala plugin prepends to every single service message here:
        // https://github.com/JetBrains/intellij-scala/blob/562e90dc4e77fbe2866e13e572c1ade93ee18e25/scala/testRunners/src/org/jetbrains/plugins/scala/testingSupport/scalaTest/TeamcityReporter.java#L11
        .map {
          case (_, MatchesEmptyLineStdoutReport()) => Chunk.empty
          case (Some(MatchesEmptyLineStdoutReport()), r2 @ TpTestReport(_, MatchesTeamCityServiceMessageEvent(_))) =>
            Chunk.single(r2)
          case (Some(r1 @ MatchesEmptyLineStdoutReport()), r2) =>
            Chunk(r1, r2)
          case (_, r2) => Chunk.single(r2)
        }
        .flattenChunks
        .foreach { report: TpTestReport =>
          val task: RIO[Console, Unit] = report.event match {
            case TpTestStarted(workerId, workerNode) =>
              startMsRef.set(System.currentTimeMillis()) *>
                zlogger.info(s"Test assigned to $workerNode $workerId ${test.fullName}")

            case MatchesTeamCityServiceMessageEvent(content) =>
              /*putStrLn(render(
                "testStdOut",
                Map(
                  "nodeId" -> leafStringId,
                  "out" -> (content + "\n")
                )
              )) *> */
              TpIntellijServiceMessageParser.parse(content) match {
                case Right(sm) =>
                  sm.name match {
                    case "testFailed" =>
                      reportedFailureRef
                        .set(Some(ReportTestFailure(
                          message = unescape(sm.attributes.getOrElse("message", "")),
                          details = unescape(sm.attributes.getOrElse("details", ""))
                        )))

                    case "message" if sm.attributes.get("status").contains("ERROR") =>
                      reportedFailureRef
                        .set(Some(ReportTestFailure(
                          message = unescape(sm.attributes.getOrElse("text", "")),
                          details = unescape(sm.attributes.getOrElse("errorDetails", ""))
                        )))
                    case _ =>
                      ZIO.unit
                  }

                case Left(reason) =>
                  zlogger.warn(s"Failed parsing service message $content $reason")
              }

            case TpTestOutputLine(content, pipe) =>
              logToConsole(render(
                if (pipe.isStdout) "testStdOut" else "testStdErr",
                Map(
                  "nodeId" -> leafStringId,
                  "out" -> (content + "\n")
                )
              ))

            case TpTestResult(exitCode) =>
              for {
                startTime <- startMsRef.get
                elapsed = System.currentTimeMillis() - startTime
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
                elapsed = System.currentTimeMillis() - startTime
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

            case _ => ???
          }

          task
        }
    } yield ()
  }
}
