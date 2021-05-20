package io.provenance.p8e.shared.index

import io.p8e.proto.ContractScope.Scope

enum class ScopeEventType(val value: String) {
    CREATED("provenance.metadata.v1.EventScopeCreated"),
    UPDATED("provenance.metadata.v1.EventScopeUpdated"),
    OWNERSHIP("scope_ownership") // TODO change to correct name after supported
}

fun String.toEventType() : ScopeEventType = when (this) {
    ScopeEventType.CREATED.value -> ScopeEventType.CREATED
    ScopeEventType.UPDATED.value -> ScopeEventType.UPDATED
    ScopeEventType.OWNERSHIP.value -> ScopeEventType.OWNERSHIP
    else -> throw IllegalStateException("Invalid event type of $this")
}

private val scopeEventValues = ScopeEventType.values().map { it.value }
fun String.isScopeEventType() = this in scopeEventValues

data class ScopeEvent(val txHash: String, val scope: Scope, val type: ScopeEventType)
