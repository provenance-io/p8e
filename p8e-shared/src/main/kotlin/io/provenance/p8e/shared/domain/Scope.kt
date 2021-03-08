package io.provenance.p8e.shared.domain

import io.p8e.proto.ContractScope.Scope
import io.p8e.util.toHex
import io.provenance.p8e.shared.util.proto
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.security.PublicKey
import java.util.UUID

object ScopeTable : UUIDTable(name = "scope", columnName = "uuid") {
    val scopeUuid = uuid("scope_uuid").index()
    val data = proto("data", Scope.getDefaultInstance())
    val lastExecutionUuid = uuid("last_execution_uuid").index().nullable()
    val publicKey = text("public_key")
}

open class ScopeEntityClass : UUIDEntityClass<ScopeRecord>(
    ScopeTable
) {
    fun findForUpdate(uuid: UUID) = find { ScopeTable.id eq uuid }.forUpdate().firstOrNull()

    private fun findForUpdate(scopeUuid: UUID, publicKey: PublicKey) =
        find { (ScopeTable.scopeUuid eq scopeUuid) and (ScopeTable.publicKey eq publicKey.toHex()) }.forUpdate().firstOrNull()

    fun findByScopeUuid(scopeUuid: UUID) = find { ScopeTable.scopeUuid eq scopeUuid }.firstOrNull()

    fun findByPublicKeyAndScopeUuid(publicKey: PublicKey, scopeUuid: UUID) =
        find { (ScopeTable.scopeUuid eq scopeUuid) and (ScopeTable.publicKey eq publicKey.toHex()) }.forUpdate().firstOrNull()

    fun findByPublicKeyAndScopeUuids(scopeUuids: List<UUID>, publicKey: PublicKey) =
        find { (ScopeTable.scopeUuid inList scopeUuids) and (ScopeTable.publicKey eq publicKey.toHex()) }.toList()

    fun <T> with(scopeUuid: UUID, publicKey: PublicKey, fn: ScopeRecord.() -> T): T =
        (findForUpdate(scopeUuid, publicKey)
            ?: new {
                this.scopeUuid = scopeUuid
                this.publicKey = publicKey.toHex()
                data = Scope.getDefaultInstance()
            })
            .fn()

    fun search(limit: Int, q: String?): List<ScopeRecord> = mutableListOf<Op<Boolean>>()
        .let { expressions ->
            val columns = ScopeTable.columns.filter { !it.equals(ScopeTable.data) }
            val query = ScopeTable.slice(columns)
            if (q != null) {
                try {
                    val uuid = UUID.fromString(q.trim())
                    expressions.add((ScopeTable.id eq uuid) or (ScopeTable.scopeUuid eq uuid))
                } catch (e: IllegalArgumentException) {
                    // invalid uuid
                }
            }

            when {
                expressions.isEmpty() -> query.selectAll()
                else -> query.select(AndOp(expressions))
            }.limit(limit).orderBy(ScopeTable.id to SortOrder.ASC)
                .let { ScopeRecord.wrapRows(it) }
                .toList()
        }
}

class ScopeRecord(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : ScopeEntityClass()
    var uuid by ScopeTable.id
    var publicKey by ScopeTable.publicKey
    var scopeUuid by ScopeTable.scopeUuid
    var data: Scope by ScopeTable.data
    var lastExecutionUuid: UUID? by ScopeTable.lastExecutionUuid
}
