package dev.toothpick.pipeline

import akka.grpc.GrpcClientSettings
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.apple.foundationdb.tuple.Versionstamp
import dev.chopsticks.dstream.DstreamWorker
import dev.chopsticks.dstream.DstreamWorker.{DstreamWorkerConfig, DstreamWorkerRetryConfig}
import dev.chopsticks.fp.ZRunnable
import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.zio_ext.{MeasuredLogging, ZIOExtensions}
import dev.chopsticks.stream.ZAkkaScope
import dev.toothpick.proto.api._
import dev.toothpick.proto.dstream._
import dev.toothpick.state.TpState
import dev.toothpick.state.TpStateDef.RunEventKey
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import pureconfig.generic.FieldCoproductHint
import zio.Schedule.{Decision, StepFunction}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.process.{Command, CommandError}
import zio.stm._
import zio.stream.ZStream
import zio.{RIO, Schedule, Task, URIO, URLayer, ZIO}

import java.time.{Instant, OffsetDateTime}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future, TimeoutException}
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.util.control.NoStackTrace

object TpExecutionPipeline {
  sealed trait TestExecutionWorkerParallelism {
    def parallelism: PosInt
  }

  object TestExecutionWorkerParallelism {
    implicit val coproductHint: FieldCoproductHint[TestExecutionWorkerParallelism] =
      new FieldCoproductHint[TestExecutionWorkerParallelism]("type") {
        override def fieldValue(name: String): String =
          name.drop("TestExecutionWorker".length).dropRight("Parallelism".length).toLowerCase
      }
  }
  final case class TestExecutionWorkerStaticParallelism(parallelism: PosInt) extends TestExecutionWorkerParallelism
  final case class TestExecutionWorkerDynamicParallelism(perWorkerCoreCount: PosInt)
      extends TestExecutionWorkerParallelism {
    override lazy val parallelism: PosInt = {
      PosInt
        .unsafeFrom(
          math.max(1, math.floor(Runtime.getRuntime.availableProcessors().toDouble / perWorkerCoreCount.value)).toInt
        )
    }
  }
  final case class TestExecutionWorkerConfig(
    clientSettings: GrpcClientSettings,
    parallelism: TestExecutionWorkerParallelism,
    assignmentTimeout: Timeout
  ) {
    def toDstreamWorkerConfig: DstreamWorkerConfig =
      DstreamWorkerConfig(clientSettings, parallelism.parallelism, assignmentTimeout)
  }

  final case class TestExecutionConfig(
    softIdleTimeout: Option[Timeout],
    softTtl: Option[FiniteDuration],
    worker: TestExecutionWorkerConfig,
    retry: DstreamWorkerRetryConfig,
    imagePullIdleTimeout: Timeout,
    imagePullCacheTtl: FiniteDuration,
    dockerPath: NonEmptyString,
    dockerRunArgs: Vector[NonEmptyString]
  )

  trait Service {
    def run(config: TestExecutionConfig): Task[Unit]
  }

  sealed trait TestProcessOutput
  final case class TestProcessStdoutLine(line: String) extends TestProcessOutput
  final case class TestProcessStderrLine(line: String) extends TestProcessOutput
  final case class TestProcessExitCode(code: Int) extends TestProcessOutput

  def resetAfter[R, In, Out](
    schedule: Schedule[R, In, Out],
    resetDuration: zio.duration.Duration
  ): Schedule[R with Clock, In, Out] = {
    def next(driver: Schedule.Driver[R with Clock, In, Out], now: OffsetDateTime, in: In) = {
      driver
        .next(in)
        .foldM(
          (_: Any) => driver.last.map(Decision.Done(_)).orDie,
          out => ZIO.succeed(Decision.Continue(out, now, loop(Some(now -> driver))))
        )
    }

    def loop(maybeLast: Option[(OffsetDateTime, Schedule.Driver[R with Clock, In, Out])])
      : StepFunction[R with Clock, In, Out] =
      (now: OffsetDateTime, in: In) => {
        maybeLast match {
          case None =>
            schedule.driver.flatMap(next(_, now, in))

          case Some((last, driver)) =>
            val elapsed = java.time.Duration.between(last, now)

            driver.reset.when(elapsed.compareTo(resetDuration) > 0) *>
              next(driver, now, in)
        }
      }

    Schedule(loop(None))
  }

  sealed abstract class ImagePullFailure(msg: String) extends RuntimeException(msg) with NoStackTrace
  final case class ImagePullCommandError(error: CommandError) extends ImagePullFailure(msg = error.toString)
  final case class ImagePullWrappedException(exception: Throwable) extends ImagePullFailure(msg = exception.getMessage)
  final case object ImagePullImageIdNotFound extends ImagePullFailure(msg = "Image was pulled but no image ID found")
  final case class ImagePullFailureWithExitCode(exitCode: Int, stderr: String)
      extends ImagePullFailure(s"exitCode=$exitCode stderr=$stderr")

  private def pullImage(
    dockerPath: NonEmptyString,
    image: String
  ): ZStream[Blocking with Clock, ImagePullFailure, String] = {
    ZStream
      .fromEffect(Command(dockerPath, "pull", image).run)
      .mapError(commandError => ImagePullCommandError(commandError))
      .flatMap { process =>
        process.stdout.linesStream
          .mapError(commandError => ImagePullCommandError(commandError))
          .concat(
            ZStream
              .fromEffect(
                process.stderr.string.zip(process.exitCode)
                  .mapError(commandError => ImagePullCommandError(commandError))
                  .flatMap { case (stderr, exitCode) =>
                    ZIO
                      .fail(ImagePullFailureWithExitCode(exitCode.code, stderr))
                      .when(exitCode.code != 0)
                  }
              ) *> ZStream.empty
          )
      }
  }

  private def runWorkers(config: TestExecutionConfig) = {
    for {
      worker <- ZIO.access[DstreamWorker[TpWorkerDistribution, TpWorkerReport, Unit]](_.get)
      dstreamWorkerConfig = config.worker.toDstreamWorkerConfig
      tmap <- TMap.make[String, TPromise[ImagePullFailure, String]]().commit
      clock <- ZIO.access[Clock](_.get)
      lastAssignmentTimeRef <- clock.instant.flatMap(now => TRef.make(now).commit)
      workerRepeatIdleLocks <- TQueue.unbounded[TPromise[Nothing, Boolean]].commit
      imagePullCacheTtlQueue <- TQueue.unbounded[(String, Instant)].commit
      _ <- zio.stream.Stream
        .fromTQueue(imagePullCacheTtlQueue)
        .mapM { case (image, time) =>
          for {
            now <- zio.clock.instant
            elapsed = java.time.Duration.between(time, now)
            delay = config.imagePullCacheTtl.toJava.minus(elapsed)
            _ <- tmap.delete(image).commit.delay(delay)
            _ <- IzLogging.zioLogger.flatMap(_.info(s"Remove image pull cache $image"))
          } yield ()
        }
        .runDrain
        .fork
      startTime <- clock.instant
      zlogger <- IzLogging.zioLogger
      nodeName = sys.env.get("NODE_NAME").orElse(sys.env.get("HOSTNAME")).getOrElse("unknown")
      _ <- {
        def lockRepetitionIfIdle(now: Instant) = {
          config.softIdleTimeout match {
            case Some(softIdleTimeout) =>
              STM
                .atomically {
                  for {
                    promise <- TPromise.make[Nothing, Boolean]
                    lastAssignmentTime <- lastAssignmentTimeRef.get
                    elapsed = java.time.Duration.between(lastAssignmentTime, now)
                    isInIdleState = elapsed.compareTo(softIdleTimeout.duration.toJava) > 1
                    _ <- STM.succeed(println(
                      s"lockRepetitionIfIdle now=$now lastAssignmentTime=$lastAssignmentTime elapsed=$elapsed softIdleTimeout=${softIdleTimeout.duration} isInIdleState=$isInIdleState"
                    ))
                    _ <- if (isInIdleState) {
                      for {
                        _ <- workerRepeatIdleLocks.offer(promise)
                        currentSize <- workerRepeatIdleLocks.size
                        _ <- workerRepeatIdleLocks
                          .takeAll
                          .flatMap(STM.foreach(_)(_.succeed(false)))
                          .when(currentSize == dstreamWorkerConfig.parallelism.value)
                      } yield ()
                    }
                    else {
                      promise.succeed(true) *> workerRepeatIdleLocks
                        .takeAll
                        .flatMap(STM.foreach(_)(_.succeed(true)))
                    }
                  } yield promise
                }
                .flatMap(_.await.commit)

            case None =>
              ZIO.succeed(true)
          }
        }

        def releaseRepetitionLock(now: Instant) = {
          config.softIdleTimeout match {
            case Some(_) =>
              STM
                .atomically {
                  for {
                    _ <- STM.succeed(println(
                      s"releaseRepetitionLock now=$now"
                    ))
                    _ <- lastAssignmentTimeRef.set(now)
                    all <- workerRepeatIdleLocks.takeAll
                    _ <- STM.foreach(all)(_.succeed(true))
                  } yield ()
                }
            case None =>
              ZIO.unit
          }
        }

        def createRepeatSchedule(workerId: Int) = {
          val softTtlSchedule = config.softTtl match {
            case Some(softTtl) =>
              Schedule
                .forever
                .mapM { (_: Any) =>
                  for {
                    now <- clock.instant
                    elapsed = java.time.Duration.between(startTime, now)
                    willRepeat = elapsed.compareTo(softTtl.toJava) <= 0
                    _ <- zlogger
                      .warn(
                        s"The total run time $elapsed has past the configured $softTtl, will not repeat $workerId"
                      )
                      .when(!willRepeat)
                  } yield willRepeat
                }
                .whileOutput(identity)

            case None =>
              Schedule.forever.as(true)
          }

          val softIdleTimeoutSchedule = config.softIdleTimeout match {
            case Some(softIdleTimeout) =>
              Schedule
                .identity[Any]
                .mapM { (_: Any) =>
                  for {
                    now <- clock.instant
                    willRepeat <- lockRepetitionIfIdle(now)
                    _ <- zlogger
                      .warn(
                        s"There has been no assignment past the configured $softIdleTimeout, will not repeat $workerId"
                      )
                      .when(!willRepeat)
                  } yield willRepeat
                }
                .whileOutput(identity)
            case None =>
              Schedule.forever.as(true)
          }

          (softTtlSchedule && softIdleTimeoutSchedule).map { case (a, b) => a && b }
        }

        def createRetrySchedule(workerId: Int): Schedule[IzLogging with Clock, Throwable, Unit] = {
          val retrySchedule = Schedule
            .identity[Throwable]
            .whileOutput(e => DstreamWorker.defaultRetryPolicy.applyOrElse(e, (_: Any) => false))

          val backoffSchedule = resetAfter(
            Schedule.exponential(config.retry.retryInitialDelay.toJava) ||
              Schedule.spaced(config.retry.retryMaxDelay.toJava),
            config.retry.retryResetAfter.toJava
          )

          (createRepeatSchedule(workerId) && (retrySchedule <* backoffSchedule))
            .onDecision {
              case Decision.Done((canRepeat, exception)) =>
                zlogger.error(s"$workerId will NOT retry $exception").when(canRepeat)
              case Decision.Continue((_, exception), interval, _) =>
                zlogger.debug(
                  s"$workerId will retry ${java.time.Duration.between(OffsetDateTime.now, interval) -> "duration"} ${exception.getMessage -> "exception"}"
                )
            }
            .unit
        }

        def pullAssignmentImage[R](
          image: String,
          onStarted: RIO[R, Unit],
          onProgress: String => RIO[R, Unit],
          onCompleted: TpImagePullingResult.Result => RIO[R, Unit]
        ) = {
          for {
            pullState <- STM.atomically {
              for {
                maybePromise <- tmap.get(image)
                ret <- maybePromise match {
                  case Some(promise) =>
                    STM.succeed(promise -> true)
                  case None =>
                    TPromise
                      .make[ImagePullFailure, String]
                      .tap(tmap.put(image, _))
                      .map(p => p -> false)
                }
              } yield ret
            }
            (pulledPromise, hasBeenPulled) = pullState

            _ <- (onStarted.mapError(ImagePullWrappedException) *> pullImage(config.dockerPath, image)
              .timeout(config.imagePullIdleTimeout.duration.toJava)
              .tap(line => onProgress(line).mapError(ImagePullWrappedException))
              .runLast
              .flatMap {
                case Some(imageId) => ZIO.succeed(imageId)
                case None => ZIO.fail(ImagePullImageIdNotFound)
              }
              .foldM(
                failure => {
                  onCompleted(
                    TpImagePullingResult.Result.Failure(TpImagePullingFailure(failure.toString))
                  ) *> STM.atomically(pulledPromise.fail(failure) *> tmap.delete(image))
                },
                imageId => {
                  onCompleted(TpImagePullingResult.Result.Success(TpImagePullingSuccess(imageId))) *> STM.atomically(
                    pulledPromise.succeed(imageId) *> imagePullCacheTtlQueue.offer(image -> Instant.now)
                  )
                }
              )
              .logResult(s"Pull $image", _.toString))
              .when(!hasBeenPulled)
            imageId <- pulledPromise.await.commit
          } yield imageId
        }

        def runAssignment(runTestId: TpRunTestId, args: Seq[String]) = {
          for {
            state <- TpState.get
            runtime <- ZIO.runtime[Blocking with MeasuredLogging]
            dispatcher <- AkkaEnv.dispatcher
            logger <- IzLogging.logger
//            _ <- zLogger.info(s"Running ${config.dockerPath} $args")
            process <- Command(config.dockerPath, args: _*).run
            scope <- ZAkkaScope.make
            logPersistenceFlow = state.api.batchTransact { batch: Vector[TpTestOutputLine] =>
              batch
                .foldLeft(state.api.transactionBuilder) { (tx, outputLine) =>
                  val lineLength = outputLine.content.length

                  // FDB has a value length limit of 100,000 bytes.
                  // An encoded UTF-8 string can occupy at worst 2 bytes per code point.
                  // Hence we're splitting when a line is longer than 45000 code points, to be safe
                  val lineLimit = 45000

                  if (lineLength > lineLimit) {
                    val partCount = math.ceil(lineLength.toDouble / lineLimit).toInt

                    outputLine
                      .content
                      .grouped(lineLimit)
                      .zipWithIndex
                      .foldLeft(tx) { case (t, (part, index)) =>
                        t.put(
                          state.keyspaces.reports,
                          RunEventKey(runTestId, Versionstamp.incomplete(t.nextVersion)),
                          TpTestReport(
                            Instant.now,
                            TpTestOutputLinePart(part, outputLine.pipe, isLast = index == partCount - 1)
                          )
                        )
                      }
                  }
                  else {
                    tx.put(
                      state.keyspaces.reports,
                      RunEventKey(runTestId, Versionstamp.incomplete(tx.nextVersion)),
                      TpTestReport(Instant.now, outputLine)
                    )
                  }
                }
                .result -> TpWorkerProgress(logLineCount = batch.size)
            }
            stdoutPersistenceFlow <- logPersistenceFlow.make(scope)
            stderrPersistenceFlow <- logPersistenceFlow.make(scope)
          } yield {
            import zio.interop.reactivestreams._

            val exitCodeFuture = runtime.unsafeRunToFuture(process.exitCode)
            val exitCodeSource = Source.future(exitCodeFuture)
              .map(c => TpWorkerResult(c.code))

            val stdoutSource = Source.fromPublisher(runtime.unsafeRun(process.stdout.linesStream.toPublisher))
              .map(TpTestOutputLine(_, TpTestOutputLine.Pipe.STDOUT))
              .via(stdoutPersistenceFlow)

            val stderrSource = Source.fromPublisher(runtime.unsafeRun(process.stderr.linesStream.toPublisher))
              .map(TpTestOutputLine(_, TpTestOutputLine.Pipe.STDERR))
              .via(stderrPersistenceFlow)

            Source
              .single(TpWorkerStarted())
              .concat(
                stdoutSource
                  .merge(stderrSource)
                  .conflate((a, b) => TpWorkerProgress(logLineCount = a.logLineCount + b.logLineCount))
                  .throttle(1, 100.millis)
              )
              .concat(exitCodeSource)
              .map(TpWorkerReport(Instant.now, _))
              .watchTermination() { (notUsed, f) =>
                implicit val ec: ExecutionContextExecutor = dispatcher

                val _ = f
                  .transformWith { _ =>
                    runtime
                      .unsafeRunToFuture(scope.close())
                      .transformWith { _ =>
                        if (!exitCodeFuture.isCompleted) {
                          logger.warn(s"Aborting $runTestId")
                          exitCodeFuture.cancel().map(_ => ())
                        }
                        else {
                          Future.successful(())
                        }
                      }
                  }
                notUsed
              }
          }
        }

        worker
          .run(dstreamWorkerConfig) { (workerId, assignment) =>
            val runTestId = TpRunTestId(assignment.runId, assignment.testId)

            for {
              now <- clock.instant
              _ <- releaseRepetitionLock(now)
              state <- TpState.get
              reportsKeyspace = state.api.columnFamily(state.keyspaces.reports)
              _ <- reportsKeyspace.putTask(
                RunEventKey(runTestId, Versionstamp.incomplete()),
                TpTestReport(Instant.now, TpTestStarted(workerId.toString, nodeName))
              )
              source <- pullAssignmentImage(
                image = assignment.image,
                onStarted = {
                  reportsKeyspace.putTask(
                    RunEventKey(runTestId, Versionstamp.incomplete()),
                    TpTestReport(
                      Instant.now,
                      TpImagePullingStarted(workerNode = nodeName, imageRef = assignment.image)
                    )
                  )
                },
                onProgress = log => {
                  reportsKeyspace.putTask(
                    RunEventKey(runTestId, Versionstamp.incomplete()),
                    TpTestReport(
                      Instant.now,
                      TpImagePullingProgress(workerNode = nodeName, imageRef = assignment.image, log = log)
                    )
                  )
                },
                onCompleted = result => {
                  reportsKeyspace.putTask(
                    RunEventKey(runTestId, Versionstamp.incomplete()),
                    TpTestReport(
                      Instant.now,
                      TpImagePullingResult(workerNode = nodeName, imageRef = assignment.image, result = result)
                    )
                  )
                }
              )
                .foldM(
                  pullFailure =>
                    ZIO.succeed(Source.single(
                      TpWorkerReport(
                        Instant.now,
                        TpWorkerException(
                          s"Failed fulling image '${assignment.image}', reason: ${pullFailure.toString}"
                        )
                      )
                    )),
                  imageId =>
                    runAssignment(runTestId, (config.dockerRunArgs.map(_.value) :+ imageId) ++ assignment.args)
                      .catchAll { commandError =>
                        ZIO.succeed(Source.single(
                          TpWorkerReport(Instant.now, TpWorkerException(s"Run error: ${commandError.toString}"))
                        ))
                      }
                )
            } yield source
          } { (workerId, task) =>
            task
              .repeat(createRepeatSchedule(workerId))
              .retry(createRetrySchedule(workerId))
              .unit
              .catchSome {
                case _: TimeoutException => ZIO.unit
              }
          }
      }
    } yield ()
  }

  def get: URIO[TpExecutionPipeline, Service] = ZIO.access[TpExecutionPipeline](_.get)

  def live: URLayer[
    AkkaEnv with Blocking with TpState with IzLogging with Clock
      with DstreamWorker[
        TpWorkerDistribution,
        TpWorkerReport,
        Unit
      ],
    TpExecutionPipeline
  ] = {
    val runnable = ZRunnable(runWorkers _)

    runnable.toLayer[Service](fn => (config: TestExecutionConfig) => fn(config))
  }
}
