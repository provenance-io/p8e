package io.provenance.os.client

import io.p8e.util.toByteString
import io.p8e.util.toOffsetDateTimeProv
import io.p8e.util.toProtoTimestampProv
import io.p8e.util.toProtoUuidProv
import io.p8e.util.toUuidProv
import io.provenance.os.domain.Bucket
import io.provenance.os.domain.Item
import io.provenance.os.domain.Object
import io.provenance.os.domain.ObjectMetadata
import io.provenance.os.domain.ObjectWithItem
import io.provenance.os.domain.PublicKey
import io.provenance.os.domain.Signature
import io.provenance.os.proto.Objects
import io.provenance.os.proto.PublicKeys

fun Objects.ObjectResponse.toDomain(): ObjectWithItem {
    val objProto = this.`object`
    val itemProto = this.item

    val metadata = ObjectMetadata(
        uuid = objProto.metadata.uuid.toUuidProv(),
        documentUuid = objProto.metadata.documentUuid.toUuidProv(),
        sha512 = objProto.metadata.sha512.toByteArray(),
        length = objProto.metadata.length,
        connectorClass = objProto.metadata.connectorClass,
        created = objProto.metadata.created.toOffsetDateTimeProv(),
        createdBy = objProto.metadata.createdBy,
        updated = objProto.metadata.takeIf { it.hasUpdated() }?.updated?.toOffsetDateTimeProv(),
        updatedBy = objProto.metadata.takeIf { it.hasUpdated() }?.updatedBy
    )
    val obj = Object(
        uuid = objProto.uuid.toUuidProv(),
        objectUuid = objProto.objectUuid.toUuidProv(),
        unencryptedSha512 = objProto.unencryptedSha512.toByteArray(),
        signatures = objProto.signaturesList.map { Signature(it.signature.toByteArray(), it.publicKey.toByteArray()) },
        uri = objProto.uri,
        bucket = objProto.bucket,
        name = objProto.name,
        metadata = metadata,
        effectiveStartDate = objProto.effectiveStartDate.toOffsetDateTimeProv(),
        effectiveEndDate = objProto.takeIf { it.hasEffectiveEndDate() }?.effectiveEndDate?.toOffsetDateTimeProv(),
        created = objProto.created.toOffsetDateTimeProv(),
        createdBy = objProto.createdBy,
        updated = objProto.takeIf { it.hasUpdated() }?.updated?.toOffsetDateTimeProv(),
        updatedBy = objProto.takeIf { it.hasUpdated() }?.updatedBy
    )
    val item = Item(
        bucket = Bucket(itemProto.bucket),
        name = itemProto.name,
        contentLength = itemProto.contentLength,
        metadata = itemProto.metadataMap
    )

    return ObjectWithItem(obj, item)
}

fun PublicKeys.PublicKeyResponse.toDomain(): PublicKey =
    PublicKey(
        uuid = this.uuid.toUuidProv(),
        publicKey = this.publicKey.toByteArray(),
        created = this.created.toOffsetDateTimeProv()
    )

fun Item.toProto(): Objects.Item =
    Objects.Item.newBuilder()
        .setBucket(bucket.name)
        .setName(name)
        .setContentLength(contentLength)
        .putAllMetadata(metadata)
        .build()

fun ObjectMetadata.toProto(): Objects.ObjectMetadata =
    Objects.ObjectMetadata.newBuilder()
        .setUuid(uuid.toProtoUuidProv())
        .setDocumentUuid(documentUuid.toProtoUuidProv())
        .setSha512(sha512.toByteString())
        .setLength(length)
        .setConnectorClass(connectorClass)
        .setCreated(created.toProtoTimestampProv())
        .setCreatedBy(createdBy)
        .also { builder ->
            updated?.run { builder.updated = this.toProtoTimestampProv() }
            updatedBy?.run { builder.updatedBy = this }
        }
        .build()

fun Signature.toProto(): Objects.Signature =
    Objects.Signature.newBuilder()
        .setSignature(signature.toByteString())
        .setPublicKey(publicKey.toByteString())
        .build()

fun Object.toProto(): Objects.Object =
    Objects.Object.newBuilder()
        .setUuid(uuid.toProtoUuidProv())
        .setObjectUuid(objectUuid.toProtoUuidProv())
        .setUnencryptedSha512(unencryptedSha512.toByteString())
        .addAllSignatures(signatures.map {it.toProto() })
        .setUri(uri)
        .setBucket(bucket)
        .setName(name)
        .setMetadata(metadata.toProto())
        .setEffectiveStartDate(effectiveStartDate.toProtoTimestampProv())
        .setCreated(created.toProtoTimestampProv())
        .setCreatedBy(createdBy)
        .also { builder ->
            effectiveEndDate?.run { builder.effectiveEndDate = this.toProtoTimestampProv() }
            updated?.run { builder.updated = this.toProtoTimestampProv() }
            updatedBy?.run { builder.updatedBy = this }
        }
        .build()

fun ObjectWithItem.toProto(): Objects.ObjectResponse =
    Objects.ObjectResponse.newBuilder()
        .setObject(obj.toProto())
        .setItem(item.toProto())
        .build()
