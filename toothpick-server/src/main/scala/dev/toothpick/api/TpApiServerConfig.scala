package dev.toothpick.api

import akka.util.Timeout
import dev.chopsticks.zio_grpc.ZioGrpcServer
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString

final case class TpApiServerConfig(
  interface: NonEmptyString,
  port: PortNumber,
  shutdownTimeout: Timeout
) extends ZioGrpcServer.Config
