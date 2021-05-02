package dev.toothpick

import dev.chopsticks.zio_grpc.ZioGrpcServer

package object api {
  type TpApiServer = ZioGrpcServer[TpApiServerBinding]
}
