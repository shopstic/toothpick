package dev.toothpick.metric

import dev.chopsticks.metric.MetricConfigs.NoLabelGaugeConfig
import dev.chopsticks.metric.{MetricGauge, MetricRegistry}
import dev.chopsticks.metric.MetricRegistry.MetricGroup
import zio.{Has, URLayer, ZManaged}

trait TpMasterMetrics {
  def informedQueueSize: MetricGauge
}

object TpMasterMetrics {
  sealed trait TpMasterMetric extends MetricGroup
  final case object InformedQueueSize extends NoLabelGaugeConfig with TpMasterMetric

  def live: URLayer[MetricRegistry[TpMasterMetric], Has[TpMasterMetrics]] = {
    val managed = for {
      registry <- ZManaged.access[MetricRegistry[TpMasterMetric]](_.get)
    } yield {
      new TpMasterMetrics {
        def informedQueueSize: MetricGauge = registry.gauge(InformedQueueSize)
      }
    }

    managed.toLayer
  }
}
