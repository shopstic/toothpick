package dev.toothpick.metric

import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.stream.ZAkkaScope
import dev.toothpick.proto.api.{TpInformRequest, TpInformResponse}
import zio.clock.Clock
import zio.{Has, UIO, URLayer, ZManaged}

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
      scope <- ZManaged.make(ZAkkaScope.make)(_.close().orDie)
      taskEnv <- ZManaged.environment[Clock]
      metrics <- ZManaged.service[TpMasterMetrics]
      zlogger <- IzLogging.zioLogger.toManaged_
    } yield {
      new Service {
        def inform(request: TpInformRequest): UIO[TpInformResponse] = {
          for {
            _ <- zlogger.info(s"Received queue informing $request")
            queueSize = math.max(0, request.queueSize)
            _ <- UIO {
              metrics.informedQueueSize.inc(queueSize)
            }
            _ <- scope.fork(
              UIO(metrics.informedQueueSize.dec(queueSize))
                .delay(request.duration.min(config.maxDuration).toJava)
                .provide(taskEnv)
            )
          } yield TpInformResponse()
        }
      }
    }

    managed.toLayer
  }
}
