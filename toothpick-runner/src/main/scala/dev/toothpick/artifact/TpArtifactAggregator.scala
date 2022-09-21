package dev.toothpick.artifact

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.zio_ext.ZIOExtensions
import dev.toothpick.proto.api.ZioApi.TpApiClient
import dev.toothpick.proto.api.{TpGetArtifactArchiveRequest, TpRunTestId}
import dev.toothpick.runner.TpRunner.TpRunnerState
import dev.toothpick.runner.TpRunnerUtils.{SuitePerProcessDistribution, TestPerProcessDistribution}
import zio.blocking.Blocking
import zio.clock.Clock
import zio.process.{Command, CommandError, ProcessInput}
import zio.stream.ZStream
import zio.{Chunk, ZIO, ZRef}

import java.io.IOException
import java.nio.file.Path
import scala.jdk.CollectionConverters._

object TpArtifactAggregator {
  private def extractArtifactArchive(runTestId: TpRunTestId, destinationPath: Path) = {
    for {
      apiClient <- ZIO.access[TpApiClient](_.get)
      byteCounter <- ZRef.make(0L)
      byteStream = apiClient
        .getArtifactArchive(TpGetArtifactArchiveRequest(runTestId))
        .tap(bs => byteCounter.update(_ + bs.size()))
        .mapConcatChunk { bs =>
          Chunk.fromIterator(bs.iterator().asScala.map(Byte.unbox))
        }
        .mapError(s => CommandError.IOError(new IOException(s.toString, s.asException())))
      _ <- Command("tar", "-xzf", "-", "-C", destinationPath.toString)
        .stdin(ProcessInput.fromStream(byteStream))
        .exitCode
      byteCount <- byteCounter.get
    } yield byteCount
  }

  def aggregate(
    runnerState: TpRunnerState,
    config: TpArtifactAggregatorConfig,
    extractionDestinationPath: Path
  ): ZIO[Blocking with TpApiClient with IzLogging with Clock, CommandError, Long] = {
    ZStream
      .fromIterable(runnerState.distributions)
      .map {
        case TestPerProcessDistribution(test) =>
          TpRunTestId(runnerState.runId, test.id)

        case SuitePerProcessDistribution(suite, _) =>
          TpRunTestId(runnerState.runId, suite.id)
      }
      .mapMPar(config.extractionParallelism.value) { runTestId =>
        extractArtifactArchive(runTestId, extractionDestinationPath)
          .logResult(
            s"Extract artifact archive runTestId=$runTestId to=$extractionDestinationPath",
            byteCount => s"$byteCount bytes"
          )
      }
      .runCount
  }
}
