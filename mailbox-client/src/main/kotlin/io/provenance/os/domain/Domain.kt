package io.provenance.os.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.provenance.os.domain.Scope.GENERAL
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.UUID
import javax.validation.constraints.NotEmpty
import javax.validation.constraints.Size

const val SHA_512_HEADER = "x-sha-512"
const val MULTI_ITEM_COUNT_HEADER = "x-multi-item-count"
const val MULTI_ITEM_BOUNDARY_HEADER = "x-multi-item-boundary"
const val MULTI_ITEM_SIZES_HEADER = "x-multi-item-sizes"
const val MULTI_ITEM_UUIDS_HEADER = "x-multi-item-uuids"
const val MULTI_ITEM_SHA512S_HEADER = "x-multi-item-sha512s"
const val MULTI_ITEM_SIGNATURES_HEADER = "x-multi-item-signatures"
const val MULTI_ITEM_SIGNATURE_PUBLIC_KEYS_HEADER = "x-multi-item-signature-pks"
const val SCOPE_HEADER = "x-scope"
const val CONTENT_LENGTH_HEADER = "x-content-length"
const val HASH_FIELD_NAME = "HASH"
const val SIGNATURE_PUBLIC_KEY_FIELD_NAME = "SIGNATURE_PUBLIC_KEY"
const val SIGNATURE_FIELD_NAME = "SIGNATURE"

enum class Scope {
    GENERAL,
    OBJECT_STORE_REPLICATION
}

data class Bucket(
    val name: String
)

data class PublicKey(
    val uuid: UUID,
    val publicKey: ByteArray,
    val created: OffsetDateTime
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PublicKey

        if (uuid != other.uuid) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (created != other.created) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + created.hashCode()
        return result
    }
}

data class PublicKeyRequest(
    val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PublicKeyRequest

        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        return publicKey.contentHashCode()
    }
}

data class IsAckedRequest(
    val objectUuid: UUID,
    @get:NotEmpty(message = "Public Key bytes must not be empty") val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IsAckedRequest

        if (objectUuid != other.objectUuid) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = objectUuid.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

data class AckRequest(
    val objectPrimaryKeyUuid: UUID,
    @get:NotEmpty(message = "Public Key bytes must not be empty") val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AckRequest

        if (objectPrimaryKeyUuid != other.objectPrimaryKeyUuid) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = objectPrimaryKeyUuid.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

data class Item(
    val bucket: Bucket,
    val name: String,
    val contentLength: Int,
    val metadata: Map<String, String>,
    @JsonIgnore val inputStream: () -> InputStream = { ByteArrayInputStream(ByteArray(0)) })

data class Object(
    val uuid: UUID,
    val objectUuid: UUID,
    val unencryptedSha512: ByteArray,
    val signatures: List<Signature>,
    val uri: String,
    val bucket: String,
    val name: String,
    val metadata: ObjectMetadata,
    val effectiveStartDate: OffsetDateTime,
    val effectiveEndDate: OffsetDateTime?,
    val created: OffsetDateTime,
    val createdBy: String,
    val updated: OffsetDateTime?,
    val updatedBy: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Object

        if (uuid != other.uuid) return false
        if (objectUuid != other.objectUuid) return false
        if (!unencryptedSha512.contentEquals(other.unencryptedSha512)) return false
        if (signatures != other.signatures) return false
        if (uri != other.uri) return false
        if (bucket != other.bucket) return false
        if (name != other.name) return false
        if (metadata != other.metadata) return false
        if (effectiveStartDate != other.effectiveStartDate) return false
        if (effectiveEndDate != other.effectiveEndDate) return false
        if (created != other.created) return false
        if (createdBy != other.createdBy) return false
        if (updated != other.updated) return false
        if (updatedBy != other.updatedBy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + objectUuid.hashCode()
        result = 31 * result + unencryptedSha512.contentHashCode()
        result = 31 * result + signatures.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + bucket.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + effectiveStartDate.hashCode()
        result = 31 * result + (effectiveEndDate?.hashCode() ?: 0)
        result = 31 * result + created.hashCode()
        result = 31 * result + createdBy.hashCode()
        result = 31 * result + (updated?.hashCode() ?: 0)
        result = 31 * result + (updatedBy?.hashCode() ?: 0)
        return result
    }
}

data class Signature(
    val signature: ByteArray,
    val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Signature

        if (!signature.contentEquals(other.signature)) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = signature.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

data class ObjectMetadata(
    val uuid: UUID,
    val documentUuid: UUID,
    val sha512: ByteArray,
    val length: Int,
    val connectorClass: String,
    val created: OffsetDateTime,
    val createdBy: String,
    val updated: OffsetDateTime?,
    val updatedBy: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ObjectMetadata

        if (uuid != other.uuid) return false
        if (documentUuid != other.documentUuid) return false
        if (!sha512.contentEquals(other.sha512)) return false
        if (length != other.length) return false
        if (created != other.created) return false
        if (createdBy != other.createdBy) return false
        if (updated != other.updated) return false
        if (updatedBy != other.updatedBy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + documentUuid.hashCode()
        result = 31 * result + sha512.contentHashCode()
        result = 31 * result + length
        result = 31 * result + created.hashCode()
        result = 31 * result + createdBy.hashCode()
        result = 31 * result + (updated?.hashCode() ?: 0)
        result = 31 * result + (updatedBy?.hashCode() ?: 0)
        return result
    }
}

data class ObjectWithItem(val obj: Object, val item: Item)

data class ObjectWithItemAndPKUuid(val obj: Object, val item: Item, val objectPrimaryKeyUuid: UUID)

data class Sha512ObjectRequest(
    @get:Size(min = 64, max = 64, message = "SHA-512 byte array must be length 64") val unencryptedSha512: ByteArray,
    @get:NotEmpty(message = "Public Key bytes must not be empty") val publicKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Sha512ObjectRequest

        if (!unencryptedSha512.contentEquals(other.unencryptedSha512)) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = unencryptedSha512.contentHashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}

data class UuidObjectRequest(val uuid: UUID)

data class PollRequest(
    @get:NotEmpty(message = "Public Key bytes must not be empty") val publicKey: ByteArray,
    val scope: Scope = GENERAL,
    val limit: Int = 10
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PollRequest

        if (!publicKey.contentEquals(other.publicKey)) return false
        if (limit != other.limit) return false

        return true
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + limit
        return result
    }
}