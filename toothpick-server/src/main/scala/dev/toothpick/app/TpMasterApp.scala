package dev.toothpick.app

import dev.chopsticks.dstream.DstreamMaster.DstreamMasterConfig
import dev.chopsticks.dstream.DstreamServer.DstreamServerConfig
import dev.chopsticks.dstream.metric.{DstreamMasterMetricsManager, DstreamStateMetricsManager}
import dev.chopsticks.fp.ZAkkaApp
import dev.chopsticks.fp.ZAkkaApp.ZAkkaAppEnv
import dev.chopsticks.fp.config.TypedConfig
import dev.chopsticks.fp.iz_logging.IzLogging
import dev.chopsticks.fp.util.LoggedRace
import dev.chopsticks.metric.log.MetricLogger
import dev.toothpick.api.{TpApiServer, TpApiServerConfig, TpApiServerImpl}
import dev.toothpick.metric.{
  PrometheusMetricServer,
  PrometheusMetricServerConfig,
  TpMasterInformedQueueConfig,
  TpMasterMetrics
}
import dev.toothpick.pipeline.TpDistributionPipeline
import dev.toothpick.pipeline.TpDistributionPipeline.TpWorkerDistributionConfig
import dev.toothpick.state.TpDbConfig
import pureconfig.ConfigConvert
import scalapb.zio_grpc.ZBindableService
import zio.{ExitCode, Has, RIO, ZIO}

import scala.collection.immutable.ListMap

final case class TpMasterAppConfig(
  db: TpDbConfig,
  prometheusMetricServer: PrometheusMetricServerConfig,
  apiServer: TpApiServerConfig,
  dstreamServer: DstreamServerConfig,
  dstreamMaster: DstreamMasterConfig,
  informedQueue: TpMasterInformedQueueConfig
)

object TpMasterAppConfig {
  // noinspection TypeAnnotation
  implicit lazy val configConvert = {
    import dev.chopsticks.util.config.PureconfigConverters._
    ConfigConvert[TpMasterAppConfig]
  }
}

object TpMasterApp extends ZAkkaApp {

  override def run(args: List[String]): RIO[ZAkkaAppEnv, ExitCode] = {
    val liveLayers = new TpLiveLayers()
    import liveLayers._
    import zio.magic._

    val typedConfig = TypedConfig.live[TpMasterAppConfig]()
    val tpDbConfig = typedConfig.map(cfg => Has(cfg.get.config.db))

    app
      .as(ExitCode(1))
      .injectSome[ZAkkaAppEnv](
        typedConfig,
        promRegistry,
        promServer,
        dstreamStateMetricFactory,
        dstreamMasterMetricFactory,
        dstreamStateMetricsManager,
        dstreamMasterMetricsManager,
        dstreamState,
        dstreamServerHandlerFactory,
        dstreamServerHandler,
        dstreamServer,
        dstreamMaster,
        tpTestDistributionPipeline,
        tpDbConfig,
        tpState,
        kvdbIoThreadPool,
        kvdbSerdesThreadPool,
        tpApiServer,
        metricLogger,
        tpMasterMetricRegistry,
        tpMasterInformedQueue,
        tpMasterMetrics
      )
  }

  // noinspection TypeAnnotation
  def app = {
    for {
      appConfig <- TypedConfig.get[TpMasterAppConfig]
      zlogger <- IzLogging.zioLogger
      apiServer <- TpApiServer.get
      distributionPipeline <- TpDistributionPipeline.get
      _ <- LoggedRace()
        .add("Metrics logging", logMetrics)
        .add("Metric server", PrometheusMetricServer.run(appConfig.prometheusMetricServer))
        .add(
          "Test distribution",
          distributionPipeline.run(TpWorkerDistributionConfig(
            master = appConfig.dstreamMaster,
            server = appConfig.dstreamServer
          ))
        )
        .add(
          "API server",
          apiServer
            .manage(appConfig.apiServer) {
              import scalapb.zio_grpc.CanBind.canBindAny
              ZBindableService.serviceDefinition(new TpApiServerImpl())
            }
            .use { binding =>
              zlogger.info(s"API server is up at: ${binding.interface} ${binding.port}") *> ZIO.never.unit
            }
        )
        .run()
    } yield ()
  }

  private def logMetrics = {
    MetricLogger
      .periodicallyCollect {
        for {
          stateMetrics <- ZIO.accessM[DstreamStateMetricsManager](_.get.activeSet)
          dstreamMasterMetrics <- ZIO.accessM[DstreamMasterMetricsManager](_.get.activeSet)
          masterMetrics <- ZIO.service[TpMasterMetrics]
        } yield {
          import MetricLogger.{sum, snapshot}

          ListMap(
            "workers" -> sum(stateMetrics)(_.workerCount),
            "offers" -> sum(stateMetrics)(_.offersTotal),
            "informed-queue" -> snapshot(masterMetrics.informedQueueSize.get),
            "queue" -> sum(stateMetrics)(_.queueSize),
            "map" -> sum(stateMetrics)(_.mapSize),
            "assignments" -> sum(dstreamMasterMetrics)(_.assignmentsTotal),
            "attempts" -> sum(dstreamMasterMetrics)(_.attemptsTotal),
            "successes" -> sum(dstreamMasterMetrics)(_.successesTotal),
            "failures" -> sum(dstreamMasterMetrics)(_.failuresTotal)
          )
        }
      }
  }
}
