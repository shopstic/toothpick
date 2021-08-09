package dev.toothpick.state

import dev.chopsticks.fp.akka_env.AkkaEnv
import dev.chopsticks.fp.zio_ext.MeasuredLogging
import dev.chopsticks.kvdb.KvdbDatabase
import dev.chopsticks.kvdb.api.KvdbDatabaseApi
import dev.chopsticks.kvdb.fdb.FdbDatabase
import dev.chopsticks.kvdb.util.{KvdbIoThreadPool, KvdbSerdesThreadPool}
import zio.{Has, RLayer, URIO, ZIO, ZManaged}
import zio.blocking.Blocking

object TpState {
  import TpStateDef._

  trait Service {
    def keyspaces: Materialization
    def backend: KvdbDatabase[BaseCf, CfSet]
    def api: KvdbDatabaseApi[BaseCf]
  }

  final case class LiveService(
    keyspaces: Materialization,
    backend: KvdbDatabase[BaseCf, CfSet],
    api: KvdbDatabaseApi[BaseCf]
  ) extends Service

  def get: URIO[TpState, Service] = ZIO.access[TpState](_.get)

  def live: RLayer[
    AkkaEnv with Blocking with MeasuredLogging with KvdbIoThreadPool with KvdbSerdesThreadPool with Has[TpDbConfig],
    TpState
  ] = {
    val managed = for {
      config <- ZManaged.service[TpDbConfig]
      backend <- FdbDatabase.manage(TpStateMaterialization, config.backend)
      dbApi <- KvdbDatabaseApi(backend).map(_.withOptions(_ => config.client)).toManaged_
    } yield LiveService(TpStateMaterialization, backend, dbApi)

    managed.toLayer
  }
}
