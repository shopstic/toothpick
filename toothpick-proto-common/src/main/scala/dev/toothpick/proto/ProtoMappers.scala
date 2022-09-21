package dev.toothpick.proto

import com.apple.foundationdb.tuple.Versionstamp
import com.google.protobuf.ByteString
import com.google.protobuf.wrappers.BytesValue
import scalapb.TypeMapper
import wvlet.airframe.ulid.ULID

object ProtoMappers {
  implicit val bytesValueUlidMapper: TypeMapper[BytesValue, ULID] =
    TypeMapper[BytesValue, ULID](bytesValue => ULID.fromBytes(bytesValue.toByteArray))(uild =>
      BytesValue.of(ByteString.copyFrom(uild.toBytes))
    )

  implicit val byteStringUlidMapper: TypeMapper[ByteString, ULID] =
    TypeMapper[ByteString, ULID](bs => ULID.fromBytes(bs.toByteArray))(uild =>
      ByteString.copyFrom(uild.toBytes)
    )

  implicit val bytesValueVersionstampMapper: TypeMapper[BytesValue, Versionstamp] =
    TypeMapper[BytesValue, Versionstamp](bytesValue => Versionstamp.fromBytes(bytesValue.value.toByteArray))(vs =>
      BytesValue.of(ByteString.copyFrom(vs.getBytes))
    )

  implicit val byteStringVersionstampMapper: TypeMapper[ByteString, Versionstamp] =
    TypeMapper[ByteString, Versionstamp](bs => Versionstamp.fromBytes(bs.toByteArray))(vs =>
      ByteString.copyFrom(vs.getBytes)
    )
}
