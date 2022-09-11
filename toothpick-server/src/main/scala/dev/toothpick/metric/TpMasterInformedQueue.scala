package dev.toothpick.metric

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.stream.ZAkkaScope
import dev.toothpick.proto.api.{TpInformRequest, TpInformResponse}
import zio.clock.Clock
import zio.{Has, UIO, URLayer, ZManaged, ZRef, ZRefM}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

final case class TpMasterInformedQueueConfig(maxDuration: FiniteDuration)

object TpMasterInformedQueue {
  trait Service {
    def inform(request: TpInformRequest): UIO[TpInformResponse]
  }

  def live(config: TpMasterInformedQueueConfig)
    : URLayer[Has[TpMasterMetrics] with Clock with IzLogging, TpMasterInformedQueue] = {
    val managed = for {
      mapRef <- ZRefM.make(Map.empty[Long, Int]).toManaged_
      scope <- ZManaged.make(ZAkkaScope.make)(_.close().orDie)
      taskEnv <- ZManaged.environment[Clock]
      metrics <- ZManaged.service[TpMasterMetrics]
      zlogger <- IzLogging.zioLogger.toManaged_
      idRef <- ZRef.make(0L).toManaged_
    } yield {
      new Service {
        def inform(request: TpInformRequest): UIO[TpInformResponse] = {
          for {
            id <- idRef.updateAndGet(_ + 1L)
            _ <- zlogger.info(s"Got $request")
            _ <- mapRef.update { map =>
              UIO {
                val updated = map.updated(id, request.queueSize)
                metrics.informedQueueSize.set(updated.values.sum)
                updated
              }
            }
            _ <- scope.fork(
              mapRef
                .update { map =>
                  UIO {
                    val updated = map.removed(id)
                    metrics.informedQueueSize.set(updated.values.sum)
                    updated
                  }
                }
                .delay(request.duration.max(config.maxDuration).toJava)
                .provide(taskEnv)
            )
          } yield TpInformResponse()
        }
      }
    }

    managed.toLayer
  }
}
