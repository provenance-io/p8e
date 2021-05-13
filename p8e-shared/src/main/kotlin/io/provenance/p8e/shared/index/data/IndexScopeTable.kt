package io.provenance.p8e.shared.index.data

import io.p8e.proto.ContractScope.Scope
import io.p8e.util.NotFoundException
import io.provenance.p8e.shared.sql.offsetDatetime
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.index.ScopeEvent
import io.provenance.p8e.shared.index.toEventType
import io.provenance.p8e.shared.util.protoBytes
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.AndOp
import org.jetbrains.exposed.sql.SortOrder.ASC
import org.jetbrains.exposed.sql.SortOrder.DESC
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.select
import java.time.OffsetDateTime
import java.util.UUID

typealias IST = IndexScopeTable
object IndexScopeTable: UUIDTable(name = "index_scope", columnName = "uuid") {
    val scopeUuid = uuid("scope_uuid")
    val scope = protoBytes("scope", Scope.getDefaultInstance())
    val blockNumber = long("block_number")
    val transactionId = text("transaction_id")
    val indexed = bool("indexed").default(false)
    val blockTransactionIndex = long("block_transaction_index")
    val eventType = text("event_type").nullable()
    val created = offsetDatetime("created")
    val updated = offsetDatetime("updated").nullable()
}

open class IndexScopeEntity : UUIDEntityClass<IndexScopeRecord>(IndexScopeTable) {

    fun find(uuid: UUID): IndexScopeRecord =
        findById(uuid) ?: throw NotFoundException("Unable to find index scope with uuid $uuid")

    fun update(
        uuid: UUID,
        indexed: Boolean = true,
        updated: OffsetDateTime = OffsetDateTime.now()
    ): IndexScopeRecord = find(uuid).apply {
        this.indexed = indexed
        this.updated = updated
    }

    fun findLatestByScopeUuid(scopeUuid: UUID): IndexScopeRecord? =
        find { IST.scopeUuid eq scopeUuid }
            .orderBy(IST.blockNumber to DESC, IST.blockTransactionIndex to DESC)
            .firstOrNull()

    fun findLatestByScopeUuids(scopeUuids: List<UUID>): List<IndexScopeRecord> =
        find { IST.scopeUuid inList scopeUuids }
            .orderBy(IST.blockNumber to DESC, IST.blockTransactionIndex to DESC)
            .distinctBy { it.scopeUuid }

    fun findByScopeUuid(
        scopeUuid: UUID,
        startWindow: OffsetDateTime = OffsetDateTime.MIN,
        endWindow: OffsetDateTime = OffsetDateTime.MAX,
        isAsc: Boolean,
        includeData: Boolean = true
    ): List<IndexScopeRecord> {
        val sortOrder = if(isAsc) { ASC } else { DESC }
        return IndexScopeTable.slice(IndexScopeTable.columns.filter { includeData || !it.equals(IndexScopeTable.scope) }).select(AndOp(listOf(
            (IST.scopeUuid eq scopeUuid),
            (IST.created greaterEq startWindow),
            (IST.created lessEq endWindow)
        ))).orderBy(IST.blockNumber to sortOrder, IST.blockTransactionIndex to sortOrder)
            .let { IndexScopeRecord.wrapRows(it) }
            .toList()
    }

    fun batchInsert(
        blockNumber: Long,
        events: List<ScopeEvent>
    ): Map<UUID, List<IndexScopeUuids>> {
        val uuidMap = mutableMapOf<UUID, MutableList<IndexScopeUuids>>()
        events.forEachIndexed { i: Int, event: ScopeEvent ->
            val uuid = UUID.randomUUID()
            new(uuid) {
                scopeUuid = event.scope.uuid.toUuidProv()
                this.scope = event.scope
                this.blockNumber = blockNumber
                transactionId = event.txHash
                blockTransactionIndex = i.toLong()
                eventType = event.type
                created = OffsetDateTime.now()
            }.also {
                uuidMap.computeIfAbsent(event.scope.uuid.toUuidProv()) { mutableListOf() }.add(it.toUuids())
            }
        }
        return uuidMap
    }
}

class IndexScopeRecord(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : IndexScopeEntity()

    var uuid by IndexScopeTable.id
    var scopeUuid by IndexScopeTable.scopeUuid
    var scope by IndexScopeTable.scope
    var blockNumber by IndexScopeTable.blockNumber
    var transactionId by IndexScopeTable.transactionId
    var indexed by IndexScopeTable.indexed
    var blockTransactionIndex by IndexScopeTable.blockTransactionIndex
    var eventType by IndexScopeTable.eventType.transform({ it?.value }, { it?.toEventType() })
    var created by IndexScopeTable.created
    var updated by IndexScopeTable.updated

    fun isIndexed(): Boolean = this.indexed
}

data class IndexScopeUuids(val indexScopeUuid: UUID, val executionUuid: UUID)
fun IndexScopeRecord.toUuids() = IndexScopeUuids(id.value, scope.lastEvent.executionUuid.toUuidProv())
