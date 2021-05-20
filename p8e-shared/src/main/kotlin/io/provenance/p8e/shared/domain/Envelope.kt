package io.provenance.p8e.shared.domain

import com.fasterxml.jackson.databind.ObjectMapper
import io.p8e.proto.ContractScope.Envelope.Status
import io.p8e.proto.ContractScope.EnvelopeState
import io.p8e.proto.ContractScope.Scope
import io.p8e.util.toHex
import io.p8e.util.toPublicKeyProto
import io.provenance.p8e.shared.sql.offsetDatetime
import io.provenance.p8e.shared.util.proto
import io.p8e.util.toUuidProv
import io.p8e.util.configureProvenance
import io.p8e.util.getExecUuid
import io.p8e.util.getPrevExecUuidNullable
import io.p8e.util.getUuid
import io.provenance.p8e.shared.sql.jsonValue
import io.provenance.p8e.shared.sql.jsonb
import org.jetbrains.exposed.dao.*
import io.provenance.p8e.shared.state.EnvelopeStateEngine
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.security.PublicKey
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.*

object EnvelopeTable : UUIDTable(name = "envelope", columnName = "uuid") {
    val groupUuid = uuid("group_uuid").index()
    val executionUuid = uuid("execution_uuid").index()
    val prevExecutionUuid = uuid("prev_execution_uuid").index().nullable()
    val scope = reference("scope_uuid", ScopeTable).index()
    val publicKey = text("public_key")
    val data = proto("data", EnvelopeState.getDefaultInstance())
    val chaincodeTransaction = jsonb<ContractTxResult>("chaincode_transaction", ObjectMapper().configureProvenance()).nullable()
    val scopeSnapshot = proto("scope_snapshot", Scope.getDefaultInstance()).nullable()
    val expirationTime = offsetDatetime("expiration_time").nullable()
    val errorTime = offsetDatetime("error_time").nullable()
    val fragmentTime = offsetDatetime("fragment_time").nullable()
    val executedTime = offsetDatetime("executed_time").nullable()
    val chaincodeTime = offsetDatetime("chaincode_time").nullable()
    val outboundTime = offsetDatetime("outbound_time").nullable()
    val inboxTime = offsetDatetime("inbox_time").nullable()
    val indexTime = offsetDatetime("index_time").nullable()
    val readTime = offsetDatetime("read_time").nullable()
    val completeTime = offsetDatetime("complete_time").nullable()
    val signedTime = offsetDatetime("signed_time").nullable()
    val createdTime = offsetDatetime("created_time").clientDefault { OffsetDateTime.now() }
    val isInvoker = bool("is_invoker").nullable()
    val status = enumerationByName("status", 256, Status::class)
    val transactionHash = varchar("transaction_hash", 64).nullable()
    val blockHeight = long("block_height").nullable()

    val contractName = data.jsonValue<String>("input", "contract", "spec", "name")
    val contractClassname = data.jsonValue<String>("contractClassname")
    val actualScopeUuid = data.jsonValue<String>("input", "ref", "scopeUuid", "value")
}

open class EnvelopeEntityClass : UUIDEntityClass<EnvelopeRecord>(
    EnvelopeTable
) {
    private val objectMapper = ObjectMapper().configureProvenance()

    fun findByGroupUuid(groupUuid: UUID) = find { EnvelopeTable.groupUuid eq groupUuid }.toList()

    fun findByGroupAndExecutionUuid(groupUuid: UUID, executionUuid: UUID) = find {
        EnvelopeTable.groupUuid eq groupUuid and (EnvelopeTable.executionUuid eq executionUuid)
    }.toList()

    fun findByExecutionUuid(executionUuid: UUID) = find {
        EnvelopeTable.executionUuid eq executionUuid
    }.toList()

    fun findByPublicKeyAndExecutionUuid(publicKey: PublicKey, executionUuid: UUID) =
        find { EnvelopeTable.executionUuid eq executionUuid }.toList()
            .firstOrNull { it.scope.publicKey.toPublicKeyProto() == publicKey.toPublicKeyProto() }

    fun findByPublicKeyAndExecutionUuidBeforeScope(publicKey: PublicKey, executionUuid: UUID) =
        find { EnvelopeTable.executionUuid eq executionUuid }.toList()
            .firstOrNull { it.publicKey == publicKey.toHex() }

    fun findForUpdate(uuid: UUID): EnvelopeRecord? = find { EnvelopeTable.id eq uuid }.forUpdate().firstOrNull()

    fun findForUpdate(publicKey: PublicKey, executionUuid: UUID): EnvelopeRecord? =
        EnvelopeTable
            .innerJoin(ScopeTable)
            .slice(EnvelopeTable.columns)
            .select { (EnvelopeTable.executionUuid eq executionUuid) and (ScopeTable.publicKey eq publicKey.toHex()) }
            .forUpdate()
            .firstOrNull()
            ?.let { EnvelopeRecord.wrapRow(it) }

    fun insert(scopeRecord: ScopeRecord, proto: EnvelopeState, publicKey: PublicKey, envelopeStateEngine: EnvelopeStateEngine): EnvelopeRecord =
        new {
            groupUuid = proto.input.getUuid()
            executionUuid = proto.input.getExecUuid()
            prevExecutionUuid = proto.input.getPrevExecUuidNullable()
            scope = scopeRecord
            this.publicKey = publicKey.toHex()

            proto.takeIf { it.hasInboxTime() }
                ?.let {
                    envelopeStateEngine.onHandleInbox(this, it)
                }

            proto.takeIf { it.isInvoker }
                ?.let {
                    envelopeStateEngine.onHandleCreated(this, it)
                }
        }

    fun findUuids(query: String, columnLabel: String = "uuid"): List<UUID> =
        TransactionManager.current().exec(query) { it.toUuids(columnLabel) }!!

    private fun ResultSet.toByteArray(columnLabel: String = "public_key"): List<ByteArray> {
        val publicKey = mutableListOf<ByteArray>()
        while (next()) {
            publicKey.add(getString(columnLabel).toByteArray())
        }
        return publicKey
    }

    private fun ResultSet.toUuids(columnLabel: String = "uuid"): List<UUID> {
        val uuids = mutableListOf<UUID>()

        while (next()) {
            uuids.add(getString(columnLabel).toUuidProv())
        }

        return uuids
    }

    private val indexQuery = """
        SELECT e.uuid
        FROM envelope e
        INNER JOIN scope s ON s.uuid = e.scope_uuid
        WHERE s.public_key = ?::text
        AND e.index_time IS NOT NULL
        AND e.complete_time IS NULL
        AND e.error_time IS NULL
        AND e.data->>? = ?
        limit 500
    """.trimIndent()

    fun findIndexesNotRead(publicKey: PublicKey, className: String) =
        findUuidsByPublicKeyAndClass(publicKey, className, indexQuery)

    private fun findUuidsByPublicKeyAndClass(publicKey: PublicKey, className: String, query: String) =
        TransactionManager.current()
        .connection
        .prepareStatement(query, false).also { statement ->
            statement.set(1, publicKey.toHex())
            statement.set(2, "contractClassname")
            statement.set(3, className)
        }.executeQuery().toUuids()

    fun search(limit: Int, q: String?, publicKey: String?, type: String?, eagerLoadScope: Boolean = true, identityUUID: UUID): List<EnvelopeRecord> = mutableListOf<Op<Boolean>>(InSubQueryOp(EnvelopeTable.publicKey, AffiliateRecord.allByIdentityUuidQuery(identityUUID).adjustSlice { slice(AffiliateTable.publicKey) }))
            .let { expressions ->
                val columns = EnvelopeTable.columns.filter { !it.equals(EnvelopeTable.data) }
                    .plus(EnvelopeTable.contractName)
                    .plus(EnvelopeTable.actualScopeUuid)
                var query = EnvelopeTable.slice(columns)
                if (q != null) {
                    try {
                        val uuid = UUID.fromString(q)
                        query = EnvelopeTable
                                .join(ScopeTable, JoinType.INNER)
                                .slice(columns)
                        expressions.add((EnvelopeTable.scope eq uuid) or (ScopeTable.scopeUuid eq uuid) or (EnvelopeTable.id eq uuid) or (EnvelopeTable.executionUuid eq uuid) or (EnvelopeTable.groupUuid eq uuid))
                    } catch (e: IllegalArgumentException) {
                        // invalid uuid
                    }
                }

                if (publicKey != null) {
                    expressions.add(EnvelopeTable.publicKey eq publicKey)
                }

                if (type != null) {
                    when (type.toLowerCase()) {
                        "fragment" -> expressions.add(EnvelopeTable.isInvoker.isNull())
                        else -> expressions.add(EnvelopeTable.isInvoker eq true)
                    }
                }

                when {
                    expressions.isEmpty() -> query.selectAll()
                    else -> query.select(AndOp(expressions))
                }.orderBy(EnvelopeTable.createdTime to SortOrder.DESC).limit(limit)
                        .let {EnvelopeRecord.wrapRows(it)}
                        .let {
                            when {
                                eagerLoadScope -> it.with(EnvelopeRecord::scope)
                                else -> it.toList()
                            }
                        }
            }
}

class EnvelopeRecord(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : EnvelopeEntityClass()

    var uuid by EnvelopeTable.id
    var groupUuid by EnvelopeTable.groupUuid
    var executionUuid by EnvelopeTable.executionUuid
    var prevExecutionUuid by EnvelopeTable.prevExecutionUuid
    var scope by ScopeRecord referencedOn EnvelopeTable.scope
    var scopeUuid by EnvelopeTable.scope
    var publicKey by EnvelopeTable.publicKey
    var data: EnvelopeState by EnvelopeTable.data
    var chaincodeTransaction by EnvelopeTable.chaincodeTransaction
    var scopeSnapshot: Scope? by EnvelopeTable.scopeSnapshot
    var expirationTime by EnvelopeTable.expirationTime
    var errorTime by EnvelopeTable.errorTime
    var fragmentTime by EnvelopeTable.fragmentTime
    var executedTime by EnvelopeTable.executedTime
    var chaincodeTime by EnvelopeTable.chaincodeTime
    var outboundTime by EnvelopeTable.outboundTime
    var inboxTime by EnvelopeTable.inboxTime
    var indexTime by EnvelopeTable.indexTime
    var readTime by EnvelopeTable.readTime
    var completeTime by EnvelopeTable.completeTime
    var signedTime by EnvelopeTable.signedTime
    var createdTime by EnvelopeTable.createdTime
    var isInvoker by EnvelopeTable.isInvoker
    var status by EnvelopeTable.status
    var transactionHash by EnvelopeTable.transactionHash
    var blockHeight by EnvelopeTable.blockHeight

    var contractName by EnvelopeTable.contractName
    var actualScopeUuid by EnvelopeTable.actualScopeUuid

    fun isExpired() = expirationTime?.let { it <= OffsetDateTime.now() } ?: false
}

