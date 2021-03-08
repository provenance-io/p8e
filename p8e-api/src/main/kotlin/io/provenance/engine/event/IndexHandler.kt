package io.provenance.engine.event

import io.p8e.proto.ContractScope
import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toOffsetDateTimeProv
import io.p8e.util.toUuidProv
import io.provenance.engine.domain.EventStatus
import io.provenance.engine.domain.toUuid
import io.provenance.engine.index.ProtoIndexer
import io.provenance.p8e.shared.index.data.IndexScopeRecord
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.engine.service.EnvelopeService
import io.provenance.engine.service.EventService
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.shared.index.ScopeEventType
import io.provenance.p8e.shared.util.P8eMDC
import io.p8e.proto.Util.UUID
import org.elasticsearch.action.DocWriteRequest.OpType
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.security.PublicKey
import java.time.OffsetDateTime
import kotlin.math.max

data class ScopeDocument(
    val scopeUuid: UUID,
    val blockNumber: Long,
    val transactionId: String,
    val created: OffsetDateTime,
    val classname: String?,
    val specification: String?,
    val executionUuid: UUID,
    val groupUuid: UUID,
    val parties: List<String>
)

@Component
class IndexHandler(
    private val envelopeService: EnvelopeService,
    private val esClient: RestHighLevelClient,
    private val eventService: EventService,
    private val protoIndexer: ProtoIndexer,
    private val affiliateService: AffiliateService
) {
    private val log = logger()

    init {
        eventService.registerCallback(Event.SCOPE_INDEX, this::indexScope)
    }

    fun indexScope(p8eEvent: P8eEvent): EventStatus? = try {
        val indexScope = transaction { IndexScopeRecord.find(p8eEvent.toUuid()) }
        val keyPairs = transaction { affiliateService.getEncryptionKeyPairs() }
        val scope = indexScope.scope
        val baseDocument = ScopeDocument(
            scopeUuid = scope.uuid,
            blockNumber = indexScope.blockNumber,
            transactionId = indexScope.transactionId,
            created = indexScope.created,
            executionUuid = scope.lastEvent.executionUuid,
            groupUuid = scope.lastEvent.groupUuid,
            classname = null,
            specification = null,
            parties = scope.partiesList.map { it.signer.encryptionPublicKey.toPublicKey().toSha512Hex() }
        )

        P8eMDC.set(scope, clear = true)

        if (indexScope.isIndexed()) {
            log.warn("Skipping indexing due to it already being complete")
        } else {
            log.info("Starting index scope ${indexScope.eventType}")

            protoIndexer.indexFields(scope, keyPairs)
                .toMutableMap()
                .also { entries ->
                    val recordGroup = when (indexScope.eventType) {
                        // there's no new record group for change scope ownership so we use the most recent
                        // record group to populate additional index fields
                        ScopeEventType.OWNERSHIP -> {
                            scope.recordGroupList.sortedByDescending {
                                max(it.audit.createdDate.toOffsetDateTimeProv().toEpochSecond(), it.audit.updatedDate.toOffsetDateTimeProv().toEpochSecond())
                            }.firstOrNull()
                                ?: throw IllegalStateException("Received ${indexScope.eventType} event from chain without record groups [group_uuid: ${scope.lastEvent.groupUuid.value}] [execution_uuid: ${scope.lastEvent.executionUuid.value}]")
                        }
                        ScopeEventType.CREATED, ScopeEventType.UPDATED, null -> {
                            scope.recordGroupList.firstOrNull { it.groupUuid == scope.lastEvent.groupUuid }
                                ?: throw IllegalStateException("Received ${indexScope.eventType} event from chain but couldn't match record group [group_uuid: ${scope.lastEvent.groupUuid.value}] [scope_group_uuid: ${scope.lastEvent.groupUuid.value}]")
                        }
                    } as ContractScope.RecordGroup

                    val document = baseDocument.copy(classname = recordGroup.classname, specification = recordGroup.specification)

                    transaction {
                        with(scope.lastEvent) {
                            EnvelopeRecord.findByGroupAndExecutionUuid(
                                this.groupUuid.toUuidProv(),
                                this.executionUuid.toUuidProv()
                            )
                        }
                    }.forEach { envelope ->
                        P8eMDC.set(envelope)
                        if (envelope.isLatestVersion()) { // skip ES indexing if newer version already indexed
                            esClient.index(
                                document.toIndexRequest(
                                    envelope.publicKey.toJavaPublicKey(),
                                    entries
                                ), RequestOptions.DEFAULT
                            )
                        } else {
                            log.warn("Skipping ES indexing for stale scope")
                        }
                        transaction { envelopeService.index(envelope, scope, indexScope.transactionId, indexScope.blockNumber) }
                    }

                    transaction { IndexScopeRecord.update(indexScope.uuid.value) }
                }

            log.info("Ending index scope")
        }

        EventStatus.COMPLETE
    } catch (t: Throwable) {
        log.error("Error indexing scope ${p8eEvent.toUuid()}", t)
        EventStatus.ERROR
    }

    private fun EnvelopeRecord.isLatestVersion(): Boolean = transaction {
        val lastExecutionTime = scope.lastExecutionUuid
            ?.let {
                EnvelopeRecord.findByPublicKeyAndExecutionUuid(publicKey.toJavaPublicKey(), it)
                    ?.data?.chaincodeTime?.toOffsetDateTimeProv()
            } ?: OffsetDateTime.MIN
        lastExecutionTime < data.chaincodeTime.toOffsetDateTimeProv()
    }

    private fun ScopeDocument.toMap(scopeData: Map<String, Any>): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["scopeUuid"] = this.scopeUuid.value
        map["blockNumber"] = this.blockNumber
        map["transactionId"] = this.transactionId
        map["created"] = this.created
        map["p8e.execution.uuid"] = this.executionUuid.value
        map["p8e.group.uuid"] = this.groupUuid.value
        map["p8e.parties.encryptionPublicKeys"] = this.parties

        this.classname?.run { map["p8e.classname"] = this }
        this.specification?.run { map["p8e.specification"] = this }

        return map.plus(scopeData)
    }

    // Prepare proto indexer output for sending into elastic search.
    private fun ScopeDocument.toIndexRequest(publicKey: PublicKey, scopeData: Map<String, Any>): IndexRequest {
        val indexName = transaction {
            affiliateService.getIndexNameByPublicKey(publicKey)
        }

        val request = IndexRequest(indexName)
        request.source(this.toMap(scopeData))
        request.id(this.scopeUuid.value)
        request.opType(OpType.INDEX)
        return request
    }
}
