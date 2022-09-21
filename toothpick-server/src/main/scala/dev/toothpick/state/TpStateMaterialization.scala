package dev.toothpick.state
import com.apple.foundationdb.tuple.Tuple
import dev.chopsticks.kvdb.codec.{
  FdbKeyDeserializer,
  FdbTupleReader,
  KeySerdes,
  PredefinedFdbKeySerializer,
  ValueSerdes
}
import dev.chopsticks.kvdb.fdb.FdbMaterialization.{KeyspaceWithVersionstampKey, KeyspaceWithVersionstampValue}
import wvlet.airframe.ulid.ULID

object TpStateMaterialization extends TpStateDef.Materialization {
  import TpStateDef._
  import dev.chopsticks.kvdb.codec.fdb_key._
  import dev.chopsticks.kvdb.codec.protobuf_value._

  implicit val ulidFdbKeySerializer: PredefinedFdbKeySerializer[ULID] = (o: Tuple, t: ULID) => o.add(t.toBytes)
  implicit val ulidFdbKeyDeserializer: FdbKeyDeserializer[ULID] =
    (in: FdbTupleReader) => Right(ULID.fromBytes(in.getBytes))
  // noinspection TypeAnnotation
  implicit val ulidKeySerdes = KeySerdes[ULID]
  implicit val ulidValueSerdes: ValueSerdes[ULID] = ValueSerdes.fromKeySerdes[ULID]

  object metadata extends MetadataKeyspace

  object hierarchy extends HierarchyKeyspace

  object distribution extends DistributionKeyspace

  object queue extends QueueKeyspace

  object status extends StatusKeyspace

  object artifacts extends ArtifactsKeyspace

  object abort extends AbortKeyspace

  object reports extends ReportsKeyspace

  override val keyspacesWithVersionstampKey: Set[KeyspaceWithVersionstampKey[BaseCf]] = Set(
    KeyspaceWithVersionstampKey(queue),
    KeyspaceWithVersionstampKey(reports)
  )

  override val keyspacesWithVersionstampValue: Set[KeyspaceWithVersionstampValue[BaseCf]] = Set.empty
}
