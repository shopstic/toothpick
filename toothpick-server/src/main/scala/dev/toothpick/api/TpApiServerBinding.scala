package dev.toothpick.api

import dev.chopsticks.zio_grpc.ZioGrpcServer
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString

final case class TpApiServerBinding(interface: NonEmptyString, port: PortNumber) extends ZioGrpcServer.Binding
