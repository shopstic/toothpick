package dev.toothpick.state

import dev.chopsticks.kvdb.api.KvdbDatabaseApi.KvdbApiClientOptions
import dev.chopsticks.kvdb.fdb.FdbDatabase.FdbDatabaseConfig
import pureconfig.ConfigConvert

final case class TpDbConfig(backend: FdbDatabaseConfig, client: KvdbApiClientOptions = KvdbApiClientOptions())

object TpDbConfig {
  //noinspection TypeAnnotation
  implicit lazy val configConvert = {
    import dev.chopsticks.util.config.PureconfigConverters._
    ConfigConvert[TpDbConfig]
  }
}
