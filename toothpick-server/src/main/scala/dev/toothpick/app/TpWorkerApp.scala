package dev.toothpick.app

import akka.grpc.GrpcClientSettings
import dev.chopsticks.dstream.metric.DstreamWorkerMetricsManager
import dev.chopsticks.dstream.util.DstreamUtils.grpcClientSettingsConfigReader
import dev.chopsticks.fp.ZAkkaApp
import dev.chopsticks.fp.ZAkkaApp.ZAkkaAppEnv
import dev.chopsticks.fp.config.TypedConfig
import dev.chopsticks.fp.util.LoggedRace
import dev.chopsticks.metric.log.MetricLogger
import dev.toothpick.metric.{PrometheusMetricServer, PrometheusMetricServerConfig}
import dev.toothpick.pipeline.TpExecutionPipeline
import dev.toothpick.pipeline.TpExecutionPipeline.TestExecutionConfig
import dev.toothpick.state.TpDbConfig
import pureconfig.ConfigReader
import zio.{ExitCode, Has, RIO, ZIO}

import scala.collection.immutable.ListMap

final case class TpWorkerAppConfig(
  db: TpDbConfig,
  prometheusMetricServer: PrometheusMetricServerConfig,
  execution: TestExecutionConfig
)

object TpWorkerApp extends ZAkkaApp {
  override def run(args: List[String]): RIO[ZAkkaAppEnv, ExitCode] = {
    val liveLayers = new TpLiveLayers()
    import liveLayers._
    import zio.magic._

    val typedConfig = grpcClientSettingsConfigReader
      .toManaged_
      .flatMap { implicit settingsConfigReader: ConfigReader[GrpcClientSettings] =>
        // noinspection TypeAnnotation
        implicit val configReader = {
          import dev.chopsticks.util.config.PureconfigConverters._
          ConfigReader[TpWorkerAppConfig]
        }
        TypedConfig.live[TpWorkerAppConfig]().build
      }
      .toLayerMany

    val tpDbConfig = typedConfig.map(cfg => Has(cfg.get.config.db))

    app
      .as(ExitCode(0))
      .injectSome[ZAkkaAppEnv](
        typedConfig,
        promRegistry,
        promServer,
        dstreamWorkerMetricFactory,
        dstreamClientMetricsManager,
        tpDbConfig,
        tpTestExecutionPipeline,
        tpState,
        dstreamWorker,
        kvdbIoThreadPool,
        kvdbSerdesThreadPool,
        dstreamClient,
        metricLogger
      )
  }

  private def logMetrics = {
    MetricLogger
      .periodicallyCollect {
        for {
          clientMetrics <- ZIO.accessM[DstreamWorkerMetricsManager](_.get.activeSet)
        } yield {
          import MetricLogger.sum

          ListMap(
            "workers" -> sum(clientMetrics)(_.workerStatus),
            "attempts" -> sum(clientMetrics)(_.attemptsTotal),
            "successes" -> sum(clientMetrics)(_.successesTotal),
            "timeouts" -> sum(clientMetrics)(_.timeoutsTotal),
            "failures" -> sum(clientMetrics)(_.failuresTotal)
          )
        }
      }
  }

  // noinspection TypeAnnotation
  def app = {
    for {
      appConfig <- TypedConfig.get[TpWorkerAppConfig]
      executionPipeline <- TpExecutionPipeline.get
      _ <- LoggedRace()
        .add("Metric server", PrometheusMetricServer.run(appConfig.prometheusMetricServer))
        .add("Metrics logging", logMetrics)
        .add("Test execution", executionPipeline.run(appConfig.execution))
        .run()

    } yield ()
  }
}
