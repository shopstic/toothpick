package dev.toothpick.api

import dev.chopsticks.zio_grpc.ZioGrpcServer
import zio.{URIO, URLayer, ZIO}
import zio.blocking.Blocking
import zio.clock.Clock

object TpApiServer {
  def get: URIO[TpApiServer, ZioGrpcServer.Service[TpApiServerBinding]] = ZIO.access[TpApiServer](_.get)

  def live: URLayer[Blocking with Clock, TpApiServer] =
    ZioGrpcServer.live[TpApiServerBinding](
      TpApiServerBinding.apply
    )
}
