package dev.toothpick

import io.prometheus.client.CollectorRegistry
import zio.Has

package object metric {
  type PrometheusCollectorRegistry = Has[CollectorRegistry]
  type PrometheusMetricServer = Has[PrometheusMetricServer.Service]
}
