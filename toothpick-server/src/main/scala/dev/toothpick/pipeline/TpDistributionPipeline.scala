package dev.toothpick.pipeline

import akka.http.scaladsl.model.http2.PeerClosedStreamException
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
import zio.{Schedule, Task, URIO, URLayer, ZIO, ZManaged}

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
            val runTestId = TpRunTestId(ctx.distribution.runId, ctx.distribution.testId)

            for {
              zlogger <- IzLogging.zioLogger
              logger <- IzLogging.logger
              workerId = report.metadata.getText(Dstreams.WORKER_ID_HEADER).getOrElse("unknown")
              nodeName = report.metadata.getText(Dstreams.WORKER_NODE_HEADER).getOrElse("unknown")
              _ <- zlogger
                .info(
                  s"Assignment $workerId $nodeName $runTestId ${ctx.distribution.args}"
                )
              workerReport <- report
                .source
                .toZAkkaSource
                .killSwitch
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
                runTestId = runTestId,
                versionstamp = ctx.versionstamp,
                event = TpTestReport(
                  time = workerReport.time,
                  event = runEvent
                )
              )
            }

        } { (ctx, task) =>
          import zio.duration._

          val runTestId = TpRunTestId(ctx.distribution.runId, ctx.distribution.testId)

          for {
            zlogger <- IzLogging.zioLogger
            state <- TpState.get

            abortWatchTask = state.api.columnFamily(state.keyspaces.abort)
              .getValueTask(_ is ctx.distribution.runId)
              .repeat(Schedule.fixed(1.second).whileInput {
                case Some(TpRunAbortRequestStatus(true)) => false
                case _ => true
              })
              .zipLeft(zlogger.info(s"Got request to abort run ${ctx.distribution.runId}"))
              .as(TpWorkerDistributionResult(
                runTestId = runTestId,
                versionstamp = ctx.versionstamp,
                event = TpTestReport(
                  time = Instant.now,
                  event = TpTestAborted.defaultInstance
                )
              ))

            out <- task
              .retryWhileM {
                // CANCEL
                case e: PeerClosedStreamException if e.numericErrorCode == 0x8 =>
                  state
                    .api
                    .columnFamily(state.keyspaces.reports)
                    .getTask(_ startsWith runTestId)
                    .fold(_ => false, _.isEmpty)
                    .tap { willRetry =>
                      zlogger.warn(s"Worker cancelled prematurely, will retry $runTestId").when(willRetry)
                    }

                case _ => ZIO.succeed(false)
              }
              .raceFirst(abortWatchTask)
              .catchAll { exception =>
                zlogger
                  .error(s"Distribution failed: $exception")
                  .as(TpWorkerDistributionResult(
                    runTestId = runTestId,
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
          .via {
            import dev.chopsticks.kvdb.codec.KeyTransformer.identityTransformer
            state.api.columnFamily(state.keyspaces.distribution).getByKeysFlow(_._2)
          }
          .collect { case ((versionstamp, _), Some(distribution)) =>
            import io.scalaland.chimney.dsl._

            TpWorkerDistributionContext(
              versionstamp,
              distribution.into[TpWorkerDistribution].transform
            )
          }
          .toZAkkaSource
          .viaZAkkaFlow(distributionFlow)
          .killSwitch
          .mapAsyncUnordered(config.master.parallelism) { result =>
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
