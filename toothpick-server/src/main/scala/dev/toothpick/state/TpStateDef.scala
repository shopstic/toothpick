package dev.toothpick.state

import com.apple.foundationdb.tuple.Versionstamp
import dev.chopsticks.kvdb.fdb.FdbMaterialization
import dev.chopsticks.kvdb.{ColumnFamilySet, KvdbDefinition, KvdbMaterialization}
import dev.toothpick.proto.api.{TpRunDistribution, TpRunTestId, TpTestNode, TpTestReport}
import dev.toothpick.proto.server.{TpRunAbortRequestStatus, TpRunMetadata, TpTestStatus}
import wvlet.airframe.ulid.ULID

object TpStateDef extends KvdbDefinition {
  final case class RunEventKey(id: TpRunTestId, sequence: Versionstamp)
  final case class RunArtifactKey(id: TpRunTestId, sequence: Int)

  trait MetadataKeyspace extends BaseCf[ULID, TpRunMetadata]
  trait HierarchyKeyspace extends BaseCf[TpRunTestId, TpTestNode]
  trait DistributionKeyspace extends BaseCf[TpRunTestId, TpRunDistribution]
  trait QueueKeyspace extends BaseCf[Versionstamp, TpRunTestId]
  trait AbortKeyspace extends BaseCf[ULID, TpRunAbortRequestStatus]
  trait StatusKeyspace extends BaseCf[TpRunTestId, TpTestStatus]
  trait ArtifactsKeyspace extends BaseCf[RunArtifactKey, Array[Byte]]
  trait ReportsKeyspace extends BaseCf[RunEventKey, TpTestReport]

  type CfSet = MetadataKeyspace
    with HierarchyKeyspace
    with DistributionKeyspace
    with QueueKeyspace
    with AbortKeyspace
    with StatusKeyspace
    with ArtifactsKeyspace
    with ReportsKeyspace

  trait Materialization extends KvdbMaterialization[BaseCf, CfSet] with FdbMaterialization[BaseCf] {
    def metadata: MetadataKeyspace
    def hierarchy: HierarchyKeyspace
    def queue: QueueKeyspace
    def distribution: DistributionKeyspace
    def abort: AbortKeyspace
    def status: StatusKeyspace
    def artifacts: ArtifactsKeyspace
    def reports: ReportsKeyspace

    override lazy val columnFamilySet: ColumnFamilySet[BaseCf, CfSet] = {
      ColumnFamilySet[BaseCf]
        .of(metadata)
        .and(hierarchy)
        .and(distribution)
        .and(queue)
        .and(abort)
        .and(status)
        .and(artifacts)
        .and(reports)
    }
  }
}
