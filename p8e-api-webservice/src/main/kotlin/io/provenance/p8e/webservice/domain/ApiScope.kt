package io.provenance.p8e.webservice.domain

import com.fasterxml.jackson.databind.JsonNode
import io.p8e.util.toHex
import io.provenance.p8e.shared.domain.ScopeRecord
import io.provenance.p8e.shared.index.data.IndexScopeRecord
import io.provenance.p8e.webservice.util.toJsonNode
import java.time.OffsetDateTime
import java.util.*

data class ApiScope(
    val uuid: UUID,
    val scopeUuid: UUID,
    val lastExecutionUuid: UUID?,
    val publicKey: String,
    val data: JsonNode?
)

fun ScopeRecord.toApi(includeData: Boolean = false) =
    ApiScope(
        id.value,
        scopeUuid,
        lastExecutionUuid,
        publicKey,
        if (includeData) data.toJsonNode() else null
    )

data class ApiIndexScope(
    val uuid: UUID,
    val scopeUuid: UUID,
    val data: JsonNode?,
    val created: OffsetDateTime,
    val publicKey: String?
)

fun IndexScopeRecord.toApi(includeData: Boolean = false) =
    ApiIndexScope(
        uuid.value,
        scopeUuid,
        if (includeData) scope.toJsonNode() else null,
        created,
        if (includeData) scope.partiesList.first().signer.signingPublicKey.toHex() else null
    )