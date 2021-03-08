package io.provenance.p8e.webservice.domain

import com.fasterxml.jackson.databind.JsonNode
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.webservice.util.toJsonNode
import java.time.OffsetDateTime
import java.util.*

data class ApiEnvelope(
    val executionId: UUID,
    val publicKey: String,
    val id: UUID,
    val groupId: UUID,
    val prevExecutionId: UUID?,
    val data: JsonNode?,
    val contractName: String?,
    val tx: JsonNode?,
    val scopeUuid: UUID?,
    val expirationTime: OffsetDateTime?,
    val errorTime: OffsetDateTime?,
    val fragmentTime: OffsetDateTime?,
    val executedTime: OffsetDateTime?,
    val chaincodeTime: OffsetDateTime?,
    val outboundTime: OffsetDateTime?,
    val inboxTime: OffsetDateTime?,
    val indexTime: OffsetDateTime?,
    val readTime: OffsetDateTime?,
    val completeTime: OffsetDateTime?,
    val signedTime: OffsetDateTime?,
    val createdTime: OffsetDateTime?,
    val isInvoker: Boolean?,
    val isExpired: Boolean,
    val status: String,
    val transactionHash: String?,
    val blockHeight: Long?
)

fun EnvelopeRecord.toApi(includeData: Boolean = false): ApiEnvelope {
    return ApiEnvelope(
        executionUuid,
        publicKey,
        id.value,
        groupUuid,
        prevExecutionUuid,
        if (includeData) data.toJsonNode() else null,
        if (includeData) data.input.contract.spec.name else contractName,
        chaincodeTransaction?.toJsonNode(),
        if (includeData) data.input.ref.scopeUuid.toUuidProv() else actualScopeUuid?.toUuidProv(),
        expirationTime,
        errorTime,
        fragmentTime,
        executedTime,
        chaincodeTime,
        outboundTime,
        inboxTime,
        indexTime,
        readTime,
        completeTime,
        signedTime,
        createdTime,
        isInvoker,
        isExpired(),
        status.name,
        transactionHash,
        blockHeight
    )
}
