package dev.toothpick.pipeline

import akka.stream.scaladsl.Sink
import com.apple.foundationdb.tuple.Versionstamp
import dev.chopsticks.dstream.DstreamMaster.DstreamMasterConfig
import dev.chopsticks.dstream.DstreamServer.DstreamServerConfig
import dev.chopsticks.dstream.{DstreamMaster, DstreamServer, DstreamServerHandler, Dstreams}
import dev.chopsticks.fp.ZRunnable
import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.zio_ext.{MeasuredLogging, ZManagedExtensions}
import dev.chopsticks.stream.ZAkkaSource.SourceToZAkkaSource
import dev.toothpick.proto.api._
import dev.toothpick.proto.dstream.{TpWorkerDistribution, TpWorkerException, TpWorkerReport, TpWorkerResult}
import dev.toothpick.proto.server.{TpRunAbortRequestStatus, TpTestStatus}
import dev.toothpick.state.TpState
import dev.toothpick.state.TpStateDef.RunEventKey
import eu.timepit.refined.auto._
import zio.{Task, URIO, URLayer, ZIO, ZManaged}

import java.time.Instant

object TpDistributionPipeline {
  final case class TpWorkerDistributionConfig(
    master: DstreamMasterConfig,
    server: DstreamServerConfig
  )

  trait Service {
    def run(config: TpWorkerDistributionConfig): Task[Unit]
  }

  final case class TpWorkerDistributionContext(versionstamp: Versionstamp, distribution: TpWorkerDistribution)
  final case class TpWorkerDistributionResult(runTestId: TpRunTestId, versionstamp: Versionstamp, event: TpTestReport)

  def get: URIO[TpDistributionPipeline, Service] = ZIO.access[TpDistributionPipeline](_.get)

  private def run(config: TpWorkerDistributionConfig) = {
    val managed = for {
      server <- ZManaged.access[DstreamServer[TpWorkerDistribution, TpWorkerReport]](_.get)
      _ <- server.manage(config.server)
        .logResult("Dstream server", _.localAddress.toString)
      master <- ZManaged
        .access[DstreamMaster[
          TpWorkerDistributionContext,
          TpWorkerDistribution,
          TpWorkerReport,
          TpWorkerDistributionResult
        ]](
          _.get
        )
      distributionFlow <- master
        .manageFlow(
          config.master,
          ctx => ZIO.succeed(ctx.distribution)
        ) {
          (ctx, report) =>
            for {
              zlogger <- IzLogging.zioLogger
              logger <- IzLogging.logger
              workerId = report.metadata.getText(Dstreams.WORKER_ID_HEADER).getOrElse("unknown")
              nodeName = report.metadata.getText(Dstreams.WORKER_NODE_HEADER).getOrElse("unknown")
              _ <- zlogger
                .info(
                  s"Assignment $workerId $nodeName ${ctx.distribution.runId} ${ctx.distribution.testId} ${ctx.distribution.args}"
                )
              workerReport <- report
                .source
                .toZAkkaSource
                .interruptibleRunWith(Sink.last)
            } yield {
              val runEvent = workerReport.event match {
                case TpWorkerResult(exitCode) =>
                  TpTestResult(exitCode)

                case TpWorkerException(message) =>
                  logger.error(s"$workerId $nodeName encountered exception: $message")
                  TpTestException(message)

                case event =>
                  logger.error(s"$workerId $nodeName ended with incorrect state: $event")
                  TpTestException(s"Worker ended before reporting result")
              }

              TpWorkerDistributionResult(
                runTestId = TpRunTestId(ctx.distribution.runId, ctx.distribution.testId),
                versionstamp = ctx.versionstamp,
                event = TpTestReport(
                  time = workerReport.time,
                  event = runEvent
                )
              )
            }
        } { (ctx, task) =>
          for {
            zlogger <- IzLogging.zioLogger
            state <- TpState.get

            abortWatchTask = state.api.columnFamily(state.keyspaces.abort)
              .watchKeySource(ctx.distribution.runId)
              .takeWhile {
                case Some(TpRunAbortRequestStatus(true)) => false
                case _ => true
              }
              .toZAkkaSource
              .interruptibleRunIgnore()
              .zipLeft(zlogger.info(s"Got request to abort run ${ctx.distribution.runId}"))
              .as(TpWorkerDistributionResult(
                runTestId = TpRunTestId(ctx.distribution.runId, ctx.distribution.testId),
                versionstamp = ctx.versionstamp,
                event = TpTestReport(
                  time = Instant.now,
                  event = TpTestAborted.defaultInstance
                )
              ))

            out <- task
              .raceFirst(abortWatchTask)
              .catchAll { exception =>
                zlogger
                  .error(s"Distribution failed: $exception")
                  .as(TpWorkerDistributionResult(
                    runTestId = TpRunTestId(ctx.distribution.runId, ctx.distribution.testId),
                    versionstamp = ctx.versionstamp,
                    event = TpTestReport(
                      time = Instant.now,
                      event = TpTestException(s"Worker failed with exception: ${exception.getMessage}")
                    )
                  ))
              }
          } yield out
        }
    } yield distributionFlow

    managed.use { distributionFlow =>
      for {
        state <- TpState.get
        result <- state
          .api
          .columnFamily(state.keyspaces.queue)
          .tailSource
          .map(TpWorkerDistributionContext.tupled)
          .via(distributionFlow)
          .toZAkkaSource
          .interruptibleMapAsyncUnordered(config.master.parallelism) { result =>
            state
              .api
              .transact(
                state
                  .api
                  .transactionBuilder
                  .delete(state.keyspaces.queue, result.versionstamp)
                  .put(state.keyspaces.status, result.runTestId, TpTestStatus(Some(result.event)))
                  .put(state.keyspaces.reports, RunEventKey(result.runTestId, Versionstamp.incomplete()), result.event)
                  .result
              )
          }
          .interruptibleRunIgnore()
      } yield result
    }
  }

  def live: URLayer[IzLogging with AkkaEnv with TpState with DstreamMaster[
    TpWorkerDistributionContext,
    TpWorkerDistribution,
    TpWorkerReport,
    TpWorkerDistributionResult
  ] with DstreamServerHandler[TpWorkerDistribution, TpWorkerReport] with MeasuredLogging with DstreamServer[
    TpWorkerDistribution,
    TpWorkerReport
  ], TpDistributionPipeline] = {
    ZRunnable(run _).toLayer[Service](fn => (config: TpWorkerDistributionConfig) => fn(config))
  }
}
