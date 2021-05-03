package dev.toothpick.runner

import dev.toothpick.proto.api.ZioApi.TpApiClient
import eu.timepit.refined.auto._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.grpc.netty.NettyChannelBuilder
import scalapb.zio_grpc.ZManagedChannel
import zio.Managed

object TpRunnerApiClient {
  final case class TpRunnerApiClientConfig(
    serverHost: NonEmptyString,
    serverPort: PortNumber,
    serverUsePlainText: Boolean
  )

  def managed(config: TpRunnerApiClientConfig): Managed[Throwable, TpApiClient.Service] = {
    TpApiClient.managed(
      ZManagedChannel[Any] {
        val builder = NettyChannelBuilder
          .forAddress(config.serverHost, config.serverPort)

        if (config.serverUsePlainText) builder.usePlaintext() else builder
      }
    )
  }
}
