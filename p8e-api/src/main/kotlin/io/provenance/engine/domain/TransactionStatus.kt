package io.provenance.engine.domain

import com.fasterxml.jackson.databind.ObjectMapper
import io.provenance.p8e.shared.sql.offsetDatetime
import io.p8e.util.configureProvenance
import io.provenance.p8e.shared.sql.jsonb
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.and
import java.time.OffsetDateTime
import java.util.*

enum class TransactionStatus {
    PENDING,
    ERROR,
    SUCCESS
}

typealias ExecutionUuids = Array<UUID>

object TransactionStatusTable: IdTable<String>("transaction_status") {
    val transactionHash = text("transaction_hash")
    val executionUuids: Column<ExecutionUuids> = jsonb("execution_uuids", ObjectMapper().configureProvenance())
    val status = enumerationByName("status", 20, TransactionStatus::class)
    val rawLog = text("raw_log").nullable()
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }
    val updated = offsetDatetime("updated").clientDefault { OffsetDateTime.now() }

    override val id = transactionHash.entityId()
}

open class TransactionStatusEntityClass: EntityClass<String, TransactionStatusRecord>(TransactionStatusTable) {
    fun insert(transactionHash: String, executionUuids: List<UUID>, status: TransactionStatus = TransactionStatus.PENDING, rawLog: String? = null) = new(transactionHash) {
        this.executionUuids = executionUuids
        this.status = status
        this.rawLog = rawLog
    }

    fun findForUpdate(transactionHash: String) = find { TransactionStatusTable.transactionHash eq transactionHash }
        .forUpdate()
        .firstOrNull()

    fun setSuccess(transactionHash: String) = findForUpdate(transactionHash)?.let {
        it.status = TransactionStatus.SUCCESS
    }

    fun getExpiredForUpdate() = find {
        (TransactionStatusTable.updated lessEq OffsetDateTime.now().minusSeconds(30))
            .and(TransactionStatusTable.status eq TransactionStatus.PENDING)
    }.forUpdate()
}

class TransactionStatusRecord(transaction_id: EntityID<String>): Entity<String>(transaction_id) {
    companion object : TransactionStatusEntityClass()

    var transactionHash by TransactionStatusTable.id
    var executionUuids by TransactionStatusTable.executionUuids.transform({ it.toTypedArray() }, { it.toList() })
    var status by TransactionStatusTable.status
    var rawLog by TransactionStatusTable.rawLog
    var created by TransactionStatusTable.created
    var updated by TransactionStatusTable.updated
}
