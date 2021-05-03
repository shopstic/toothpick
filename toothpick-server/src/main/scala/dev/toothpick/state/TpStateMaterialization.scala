package dev.toothpick.state
import dev.chopsticks.kvdb.codec.ValueSerdes
import dev.chopsticks.kvdb.fdb.FdbMaterialization.{KeyspaceWithVersionstampKey, KeyspaceWithVersionstampValue}

import java.util.UUID

object TpStateMaterialization extends TpStateDef.Materialization {
  import TpStateDef._
  import dev.chopsticks.kvdb.codec.fdb_key._
  import dev.chopsticks.kvdb.codec.protobuf_value._

  implicit val uuidValueSerdes: ValueSerdes[UUID] = ValueSerdes.fromKeySerdes[UUID]

  object hierarchy extends HierarchyKeyspace

  object distribution extends DistributionKeyspace

  object queue extends QueueKeyspace

  object status extends StatusKeyspace

  object abort extends AbortKeyspace

  object reports extends ReportsKeyspace

  override val keyspacesWithVersionstampKey: Set[KeyspaceWithVersionstampKey[BaseCf]] = Set(
    KeyspaceWithVersionstampKey(queue),
    KeyspaceWithVersionstampKey(reports)
  )

  override val keyspacesWithVersionstampValue: Set[KeyspaceWithVersionstampValue[BaseCf]] = Set.empty
}
