package dev.toothpick.app

import akka.grpc.GrpcClientSettings
import dev.chopsticks.dstream.DstreamServerHandlerFactory.DstreamServerPartialHandler
import dev.chopsticks.dstream._
import dev.chopsticks.dstream.metric.DstreamMasterMetrics.DstreamMasterMetric
import dev.chopsticks.dstream.metric.DstreamStateMetrics.DstreamStateMetric
import dev.chopsticks.dstream.metric.DstreamWorkerMetrics.DstreamWorkerMetric
import dev.chopsticks.dstream.metric.{
  DstreamClientMetricsManager,
  DstreamMasterMetricsManager,
  DstreamStateMetricsManager
}
import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.config.TypedConfig
import dev.chopsticks.kvdb.util.{KvdbIoThreadPool, KvdbSerdesThreadPool}
import dev.chopsticks.metric.log.MetricLogger
import dev.chopsticks.metric.prom.{PromMetricRegistry, PromMetricRegistryFactory}
import dev.toothpick.metric.{PrometheusMetricServer, TpMasterInformedQueue, TpMasterMetrics}
import dev.toothpick.pipeline.TpDistributionPipeline.{TpWorkerDistributionContext, TpWorkerDistributionResult}
import dev.toothpick.pipeline.{TpDistributionPipeline, TpExecutionPipeline}
import dev.toothpick.api.TpApiServer
import dev.toothpick.metric.TpMasterMetrics.TpMasterMetric
import dev.toothpick.proto.dstream._
import dev.toothpick.state.TpState
import io.prometheus.client.CollectorRegistry
import zio.ZLayer

import java.util.concurrent.TimeUnit

//noinspection TypeAnnotation
final class TpLiveLayers(metricPrefix: String = "tp") {
  lazy val promRegistry = ZLayer.succeed(CollectorRegistry.defaultRegistry)
  lazy val promServer = PrometheusMetricServer.live

  lazy val dstreamStateMetricFactory = PromMetricRegistryFactory.live[DstreamStateMetric](metricPrefix)
  lazy val dstreamWorkerMetricFactory = PromMetricRegistryFactory.live[DstreamWorkerMetric](metricPrefix)
  lazy val dstreamMasterMetricFactory = PromMetricRegistryFactory.live[DstreamMasterMetric](metricPrefix)

  lazy val dstreamStateMetricsManager = DstreamStateMetricsManager.live
  lazy val dstreamClientMetricsManager = DstreamClientMetricsManager.live
  lazy val dstreamMasterMetricsManager = DstreamMasterMetricsManager.live

  lazy val dstreamState = DstreamState.manage[TpWorkerDistribution, TpWorkerReport](metricPrefix).toLayer
  lazy val dstreamServerHandlerFactory =
    DstreamServerHandlerFactory.live[TpWorkerDistribution, TpWorkerReport] { handle =>
      AkkaEnv.actorSystem.map { implicit as =>
        DstreamServerPartialHandler(
          TpDstreamPowerApiHandler.partial(handle(_, _)),
          TpDstream
        )
      }
    }
  lazy val dstreamServerHandler = DstreamServerHandler.live[TpWorkerDistribution, TpWorkerReport]
  lazy val dstreamClient = DstreamClient
    .live[TpWorkerDistribution, TpWorkerReport] { settings: GrpcClientSettings =>
      AkkaEnv.actorSystem.map { implicit as =>
        TpDstreamClient(settings
          .withChannelBuilderOverrides(
            _
              .keepAliveWithoutCalls(true)
              .keepAliveTime(5, TimeUnit.SECONDS)
              .keepAliveTimeout(3, TimeUnit.SECONDS)
          ))
      }
    } { (client, workerId) =>
      client
        .run()
        .addHeader(Dstreams.WORKER_ID_HEADER, workerId.toString)
        .addHeader(
          Dstreams.WORKER_NODE_HEADER,
          sys.env.get("NODE_NAME").orElse(sys.env.get("HOSTNAME")).getOrElse("unknown")
        )
    }

  lazy val dstreamServer = DstreamServer.live[TpWorkerDistribution, TpWorkerReport]
  lazy val dstreamMaster =
    DstreamMaster.live[TpWorkerDistributionContext, TpWorkerDistribution, TpWorkerReport, TpWorkerDistributionResult]
  lazy val dstreamWorker = DstreamWorker.live[TpWorkerDistribution, TpWorkerReport, Unit]

  lazy val kvdbIoThreadPool = KvdbIoThreadPool.live
  lazy val kvdbSerdesThreadPool = KvdbSerdesThreadPool.fromDefaultAkkaDispatcher()
  lazy val tpTestDistributionPipeline = TpDistributionPipeline.live
  lazy val tpTestExecutionPipeline = TpExecutionPipeline.live
  lazy val tpApiServer = TpApiServer.live
  lazy val tpState = TpState.live
  lazy val metricLogger = MetricLogger.live()

  lazy val tpMasterMetricRegistry = PromMetricRegistry.live[TpMasterMetric](s"${metricPrefix}_master")
  lazy val tpMasterMetrics = TpMasterMetrics.live
  lazy val tpMasterInformedQueue = TypedConfig
    .get[TpMasterAppConfig]
    .toLayer
    .flatMap(config => TpMasterInformedQueue.live(config.get.informedQueue))
}
