package dev.toothpick.metric

import akka.http.scaladsl.Http
import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.zio_ext.{MeasuredLogging, ZManagedExtensions}
import eu.timepit.refined.types.all.{NonEmptyString, PortNumber}
import io.prometheus.client.exporter.common.TextFormat
import pureconfig.ConfigConvert
import zio.{RIO, RLayer, Task, TaskManaged, ZIO, ZManaged}

import java.io.CharArrayWriter
import scala.concurrent.Future
import eu.timepit.refined.auto._

import scala.concurrent.duration._

final case class PrometheusMetricServerConfig(
  port: PortNumber,
  interface: NonEmptyString = "0.0.0.0",
  metricsPath: NonEmptyString = "/metrics"
)

object PrometheusMetricServerConfig {
  // noinspection TypeAnnotation
  implicit val configConvert = {
    import dev.chopsticks.util.config.PureconfigConverters._
    ConfigConvert[PrometheusMetricServerConfig]
  }
}

final case class PrometheusMetricsServerBinding(binding: Http.ServerBinding) extends AnyVal

object PrometheusMetricServer {
  trait Service {
    def manage(config: PrometheusMetricServerConfig): TaskManaged[PrometheusMetricsServerBinding]
  }

  def run(config: PrometheusMetricServerConfig): RIO[MeasuredLogging with PrometheusMetricServer, Unit] = {
    for {
      server <- ZIO.access[PrometheusMetricServer](_.get)
      _ <- server
        .manage(config)
        .logResult("Prometheus metric server", _.binding.localAddress.toString)
        .useForever
        .unit
    } yield ()
  }

  def live: RLayer[PrometheusCollectorRegistry with AkkaEnv, PrometheusMetricServer] = {
    val managed = for {
      akkaService <- ZManaged.access[AkkaEnv](_.get)
      registry <- ZManaged.access[PrometheusCollectorRegistry](_.get)
    } yield {
      new Service {
        override def manage(config: PrometheusMetricServerConfig): TaskManaged[PrometheusMetricsServerBinding] = {
          ZManaged
            .make {
              import akka.http.scaladsl.server.Directives._
              import akkaService.{actorSystem, dispatcher}

              val route = {
                get {
                  concat(
                    path(config.metricsPath.value) {
                      complete {
                        Future {
                          val writer = new CharArrayWriter()
                          TextFormat.write004(writer, registry.metricFamilySamples())
                          writer.toString
                        }
                      }
                    }
                  )
                }
              }

              Task.fromFuture(_ => Http().newServerAt(config.interface.value, config.port.value).bind(route))
            } { binding =>
              Task.fromFuture { _ =>
                binding.terminate(5.seconds)
              }.ignore
            }
            .map(PrometheusMetricsServerBinding)
        }
      }
    }

    managed.toLayer
  }
}
