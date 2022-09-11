package dev.toothpick.metric

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.stream.ZAkkaScope
import dev.toothpick.proto.api.{TpInformRequest, TpInformResponse}
import zio.clock.Clock
import zio.{Has, UIO, URLayer, ZManaged, ZRefM}

import java.util.UUID
import scala.jdk.DurationConverters.ScalaDurationOps

object TpMasterInformedQueue {
  trait Service {
    def inform(request: TpInformRequest): UIO[TpInformResponse]
  }

  def live: URLayer[Has[TpMasterMetrics] with Clock with IzLogging, TpMasterInformedQueue] = {
    val managed = for {
      ref <- ZRefM.make(Map.empty[UUID, Int]).toManaged_
      scope <- ZManaged.make(ZAkkaScope.make)(_.close().orDie)
      taskEnv <- ZManaged.environment[Clock]
      metrics <- ZManaged.service[TpMasterMetrics]
      zlogger <- IzLogging.zioLogger.toManaged_
    } yield {
      new Service {
        def inform(request: TpInformRequest): UIO[TpInformResponse] = {
          for {
            uuid <- UIO(UUID.randomUUID())
            _ <- zlogger.info(s"Got $request")
            _ <- ref.update { map =>
              UIO {
                val updated = map.updated(uuid, request.queueSize)
                metrics.informedQueueSize.set(updated.values.sum)
                updated
              }
            }
            _ <- scope.fork(
              ref
                .update { map =>
                  UIO {
                    val updated = map.removed(uuid)
                    metrics.informedQueueSize.set(updated.values.sum)
                    updated
                  }
                }
                .delay(request.duration.toJava)
                .provide(taskEnv)
            )
          } yield TpInformResponse()
        }
      }
    }

    managed.toLayer
  }
}
