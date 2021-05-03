package dev.toothpick.api

import akka.stream.scaladsl.Sink
import com.apple.foundationdb.tuple.Versionstamp
import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.kvdb.util.KvdbException.ConditionalTransactionFailedException
import dev.toothpick.proto.api.ZioApi.RTpApi
import dev.toothpick.proto.api._
import dev.toothpick.proto.server.{TpRunAbortRequestStatus, TpTestStatus}
import dev.toothpick.state.TpState
import io.grpc.Status
import zio.stream.ZStream
import zio.{Task, UIO, ZIO}

import java.util.UUID

final class TpApiServerImpl extends RTpApi[TpState with AkkaEnv with IzLogging] {
  override def run(request: TpRunRequest): ZIO[TpState with IzLogging, Status, TpRunResponse] = {
    val task = for {
      state <- TpState.get
      runId <- UIO(UUID.randomUUID())
      actions <- Task {
        val tx = request
          .hierarchy
          .foldLeft(state.api.transactionBuilder) { case (t, (id, node)) =>
            val runTestId = TpRunTestId(runId, id)
            t.put(state.keyspaces.hierarchy, runTestId, node)
          }

        request
          .runOptions
          .foldLeft(tx) { case (t, (id, runOptions)) =>
            val runTestId = TpRunTestId(runId, id)

            t
              .put(
                state.keyspaces.distribution,
                runTestId,
                TpRunDistribution(runId = runId, testId = id, image = runOptions.image, args = runOptions.args)
              )
              .put(
                state.keyspaces.queue,
                Versionstamp.incomplete(id),
                runTestId
              )
              .put(state.keyspaces.status, runTestId, TpTestStatus.defaultInstance)
              .put(state.keyspaces.abort, runId, TpRunAbortRequestStatus.defaultInstance)
          }
          .result
      }
      _ <- state.api.transact(actions)
    } yield TpRunResponse(runId)

    task
      .tapCause { e =>
        IzLogging.zioLogger.flatMap(_.error(s"Failed handling run request: ${e.squash -> "exception"}"))
      }
      .mapError(Status.INTERNAL.withCause)
  }

  override def abort(request: TpAbortRequest): ZIO[TpState with AkkaEnv with IzLogging, Status, TpAbortResponse] = {
    val task = for {
      state <- TpState.get
      api = state.api
      keyspace = state.keyspaces.abort
      _ <- api.conditionallyTransact(
        reads = api.readTransactionBuilder
          .get(keyspace, request.runId)
          .result,
        condition = _.headOption.flatten.exists { case (_, valueBytes) =>
          !TpRunAbortRequestStatus.parseFrom(valueBytes).requested
        },
        actions = api.transactionBuilder
          .put(keyspace, request.runId, TpRunAbortRequestStatus(true))
          .result
      )
    } yield TpAbortResponse(true)

    task
      .catchSome {
        case _: ConditionalTransactionFailedException => ZIO.succeed(TpAbortResponse(false))
      }
      .tapCause { e =>
        IzLogging.zioLogger.flatMap(_.error(s"Failed handling abort request: ${e.squash -> "exception"}"))
      }
      .mapError(Status.INTERNAL.withCause)
  }

  override def watch(request: TpWatchRequest): ZStream[TpState with AkkaEnv, Status, TpWatchResponse] = {
    import zio.interop.reactivestreams._

    ZStream
      .fromEffect {
        for {
          state <- TpState.get
          runTestId = request.runTestId
          stream <- AkkaEnv.actorSystem.map { implicit as =>
            state
              .api
              .columnFamily(state.keyspaces.reports)
              .tailSource(
                c => {
                  request.versionstamp match {
                    case Some(vs) =>
                      c.gt(runTestId -> vs).startsWith(runTestId)
                    case None =>
                      c startsWith runTestId
                  }
                },
                _ startsWith runTestId
              )
              .map { case (key, report) =>
                val ended = report match {
                  case TpTestReport(_, _: TpTestException) => true
                  case TpTestReport(_, _: TpTestResult) => true
                  case _ => false
                }

                TpWatchResponse(ended = ended, versionstamp = key.sequence, report = report)
              }
              .takeWhile(!_.ended, inclusive = true)
              .runWith(Sink.asPublisher(false))
              .toStream()
          }
        } yield stream
      }
      .flatten
      .mapError(Status.INTERNAL.withCause)
  }

  override def getDistributions(request: TpGetDistributionsRequest)
    : ZStream[TpState with AkkaEnv, Status, TpRunDistribution] = {
    import zio.interop.reactivestreams._

    ZStream
      .fromEffect {
        for {
          state <- TpState.get
          runId = request.runId
          stream <- AkkaEnv.actorSystem.map { implicit as =>
            state
              .api
              .columnFamily(state.keyspaces.distribution)
              .valueSource(_ startsWith runId, _ startsWith runId)
              .runWith(Sink.asPublisher(false))
              .toStream()
          }
        } yield stream
      }
      .flatten
      .mapError(Status.INTERNAL.withCause)
  }
}
