package dev.toothpick.proto

import com.apple.foundationdb.tuple.Versionstamp
import com.google.protobuf.ByteString
import com.google.protobuf.wrappers.BytesValue
import scalapb.TypeMapper

object Mappers {
  implicit val bytesValueVersionstampMapper: TypeMapper[BytesValue, Versionstamp] =
    TypeMapper[BytesValue, Versionstamp](bytesValue => Versionstamp.fromBytes(bytesValue.value.toByteArray))(vs =>
      BytesValue.of(ByteString.copyFrom(vs.getBytes))
    )

  implicit val byteStringVersionstampMapper: TypeMapper[ByteString, Versionstamp] =
    TypeMapper[ByteString, Versionstamp](bs => Versionstamp.fromBytes(bs.toByteArray))(vs =>
      ByteString.copyFrom(vs.getBytes)
    )
}
