package dev.toothpick.api

import akka.stream.scaladsl.Sink
import com.apple.foundationdb.tuple.Versionstamp
import com.google.protobuf.ByteString
import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.zio_ext.{MeasuredLogging, ZIOExtensions}
import dev.chopsticks.kvdb.util.KvdbException.{ConditionalTransactionFailedException, SeekFailure}
import dev.chopsticks.stream.ZAkkaSource.SourceToZAkkaSource
import dev.toothpick.metric.TpMasterInformedQueue
import dev.toothpick.proto.api.ZioApi.RTpApi
import dev.toothpick.proto.api._
import dev.toothpick.proto.server.{TpRunAbortRequestStatus, TpRunMetadata, TpTestStatus}
import dev.toothpick.state.TpState
import io.grpc.Status
import wvlet.airframe.ulid.ULID
import zio.stream.ZStream
import zio.{Task, UIO, ZIO}

final class TpApiServerImpl extends RTpApi[TpState with AkkaEnv with MeasuredLogging with TpMasterInformedQueue] {
  def inform(request: TpInformRequest)
    : ZIO[TpState with AkkaEnv with MeasuredLogging with TpMasterInformedQueue, Status, TpInformResponse] = {
    ZIO.accessM[TpMasterInformedQueue](_.get.inform(request))
  }

  override def run(request: TpRunRequest): ZIO[TpState with MeasuredLogging, Status, TpRunResponse] = {
    val task = for {
      state <- TpState.get
      runId <- UIO(ULID.newULID)
      actions <- Task {
        val tx = state.api.transactionBuilder
          .put(
            state.keyspaces.metadata,
            runId,
            TpRunMetadata(
              id = runId,
              seedArtifactArchive = request.seedArtifactArchive
            )
          )

        val txWithHierarchy = request
          .hierarchy
          .foldLeft(tx) { case (t, (id, node)) =>
            val runTestId = TpRunTestId(runId, id)
            t.put(state.keyspaces.hierarchy, runTestId, node)
          }

        request
          .runOptions
          .foldLeft(txWithHierarchy) { case (t, (id, runOptions)) =>
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
      .log(
        s"Process run request " +
          s"run_options_count=${request.runOptions.size} " +
          s"hierarchy_count=${request.hierarchy.size} " +
          s"seed_artifact_archive_size=${request.seedArtifactArchive.size()}"
      )
      .tapCause { e =>
        IzLogging.zioLogger.flatMap(_.error(s"Failed handling run request: ${e.squash -> "exception"}"))
      }
      .mapError(Status.INTERNAL.withCause)
  }

  override def abort(request: TpAbortRequest)
    : ZIO[TpState with AkkaEnv with MeasuredLogging, Status, TpAbortResponse] = {
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
      .mapError {
        case _: SeekFailure => Status.NOT_FOUND
        case e => Status.INTERNAL.withCause(e)
      }
  }

  override def getHierarchy(request: TpGetHierarchyRequest): ZStream[TpState with AkkaEnv, Status, TpTestNode] = {
    import zio.interop.reactivestreams._

    ZStream
      .fromEffect {
        for {
          state <- TpState.get
          runId = request.runId
          stream <- AkkaEnv.actorSystem.map { implicit as =>
            state
              .api
              .columnFamily(state.keyspaces.hierarchy)
              .valueSource(_ startsWith runId, _ startsWith runId)
              .runWith(Sink.asPublisher(false))
              .toStream()
          }
        } yield stream
      }
      .flatten
      .mapError {
        case _: SeekFailure => Status.NOT_FOUND
        case e => Status.INTERNAL.withCause(e)
      }
  }

  override def getArtifactArchive(request: TpGetArtifactArchiveRequest)
    : ZStream[TpState with AkkaEnv, Status, ByteString] = {
    import zio.interop.reactivestreams._

    ZStream
      .fromEffect {
        for {
          state <- TpState.get
          runTestId = request.runTestId
          stream <- AkkaEnv.actorSystem.map { implicit as =>
            state
              .api
              .columnFamily(state.keyspaces.artifacts)
              .valueSource(_ startsWith runTestId, _ startsWith runTestId)
              .map(bytes => ByteString.copyFrom(bytes))
              .runWith(Sink.asPublisher(false))
              .toStream()
          }
        } yield stream
      }
      .flatten
      .mapError {
        case _: SeekFailure => Status.NOT_FOUND
        case e => Status.INTERNAL.withCause(e)
      }
  }

  override def gc(request: TpGcRequest)
    : ZIO[TpState with AkkaEnv with MeasuredLogging with TpMasterInformedQueue with Any, Status, TpGcResponse] = {
    val oldestEpochMillis = request.oldestTime.toEpochMilli
    val parallelism = math.min(1, request.parallelism)

    val task = for {
      state <- TpState.get
      _ <- state.api
        .columnFamily(state.keyspaces.metadata)
        .source
        .collect {
          case (k, _) if k.timestamp < oldestEpochMillis =>
            k
        }
        .toZAkkaSource
        .killSwitch
        .mapAsyncUnordered(parallelism) { runId =>
          val purge = for {
            actions <- Task {
              state.api.transactionBuilder
                .delete(state.keyspaces.metadata, runId)
                .delete(state.keyspaces.abort, runId)
                .deletePrefix(state.keyspaces.hierarchy, runId)
                .deletePrefix(state.keyspaces.distribution, runId)
                .deletePrefix(state.keyspaces.status, runId)
                .deletePrefix(state.keyspaces.artifacts, runId)
                .deletePrefix(state.keyspaces.reports, runId)
                .result
            }
            _ <- state.api.transact(actions)
          } yield runId

          purge.log(s"Purge run_id=$runId")
        }
        .interruptibleRunIgnore()
    } yield TpGcResponse()

    task
      .tapCause { e =>
        IzLogging.zioLogger.flatMap(_.error(s"Failed handling gc request: ${e.squash -> "exception"}"))
      }
      .mapError(Status.INTERNAL.withCause)
  }
}
