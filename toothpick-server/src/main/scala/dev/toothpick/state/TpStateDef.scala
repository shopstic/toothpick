package dev.toothpick.state

import com.apple.foundationdb.tuple.Versionstamp
import dev.chopsticks.kvdb.fdb.FdbMaterialization
import dev.chopsticks.kvdb.{ColumnFamilySet, KvdbDefinition, KvdbMaterialization}
import dev.toothpick.proto.api.{TpRunTestId, TpTestNode, TpTestReport}
import dev.toothpick.proto.dstream.TpWorkerDistribution
import dev.toothpick.proto.server.{TpRunAbortRequestStatus, TpTestStatus}

import java.util.UUID

object TpStateDef extends KvdbDefinition {
  final case class RunEventKey(id: TpRunTestId, sequence: Versionstamp)

  trait HierarchyKeyspace extends BaseCf[TpRunTestId, TpTestNode]
  trait QueueKeyspace extends BaseCf[Versionstamp, TpWorkerDistribution]
  trait AbortKeyspace extends BaseCf[UUID, TpRunAbortRequestStatus]
  trait StatusKeyspace extends BaseCf[TpRunTestId, TpTestStatus]
  trait ReportsKeyspace extends BaseCf[RunEventKey, TpTestReport]

  type CfSet = HierarchyKeyspace with QueueKeyspace with AbortKeyspace with StatusKeyspace with ReportsKeyspace

  trait Materialization extends KvdbMaterialization[BaseCf, CfSet] with FdbMaterialization[BaseCf] {
    def hierarchy: HierarchyKeyspace
    def queue: QueueKeyspace
    def abort: AbortKeyspace
    def status: StatusKeyspace
    def reports: ReportsKeyspace

    override lazy val columnFamilySet: ColumnFamilySet[BaseCf, CfSet] = {
      ColumnFamilySet[BaseCf]
        .of(hierarchy)
        .and(queue)
        .and(abort)
        .and(status)
        .and(reports)
    }
  }
}
