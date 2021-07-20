package io.provenance.engine.stream

import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toProtoUuidProv
import io.provenance.engine.config.EventStreamProperties
import io.provenance.engine.domain.EventStreamRecord
import io.provenance.engine.domain.TransactionStatusRecord
import io.provenance.engine.service.ChaincodeInvokeService
import io.provenance.p8e.shared.index.data.IndexScopeRecord
import io.provenance.engine.service.EventService
import io.provenance.engine.service.ProvenanceGrpcService
import io.provenance.engine.stream.domain.Attribute
import io.provenance.engine.stream.domain.EventBatch
import io.provenance.engine.stream.domain.EventStreamResponseObserver
import io.provenance.engine.stream.domain.StreamEvent
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.shared.index.ScopeEvent
import io.provenance.p8e.shared.index.ScopeEventType
import io.provenance.p8e.shared.index.toEventType
import io.provenance.p8e.shared.util.P8eMDC
import io.provenance.p8e.shared.util.toBlockHeight
import io.provenance.p8e.shared.util.toTransactionHashes
import io.provenance.pbc.esc.ApiKeyCallCredentials
import io.provenance.pbc.esc.EventStreamApiKey
import io.provenance.pbc.esc.StreamClientParams
import io.provenance.pbc.ess.proto.EventStreamGrpc
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Component
class ScopeStream(
    private val eventService: EventService,
    eventStreamProperties: EventStreamProperties,
    private val eventStreamFactory: EventStreamFactory,
    private val provenanceGrpcService: ProvenanceGrpcService,
) {
    private val log = logger()

    // We're only interested in scope events from pbc
    private val eventTypes = ScopeEventType.values().map { it.value }

    // The current event stream ID
    private val eventStreamId = java.util.UUID.fromString(eventStreamProperties.id)

    // The p8e -> pbc epoch, before which, no scopes exist on chain.
    private val epochHeight = eventStreamProperties.epoch.toLong()

    // This is scheduled so if the event streaming server or its proxied blockchain daemon node go down,
    // we'll attempt to re-connect after a fixed delay.
    @Scheduled(fixedDelay = 30_000)
    fun consumeEventStream() {
        // Initialize event stream state and determine start height
        val record = transaction { EventStreamRecord.findById(eventStreamId) }
        val lastHeight = record?.lastBlockHeight
            ?: transaction { EventStreamRecord.insert(eventStreamId, epochHeight) }.lastBlockHeight

        val responseObserver = EventStreamResponseObserver<EventBatch>() { batch ->
            queueIndexScopes(batch.height, batch.scopes())
        }

        log.info("Starting event stream at height ${lastHeight + 1}")

        eventStreamFactory.getStream(eventTypes, lastHeight + 1, responseObserver)
            .streamEvents()

        while (true) {
            val isComplete = responseObserver.finishLatch.await(60, TimeUnit.SECONDS)

            when (Pair(isComplete, responseObserver.error)) {
                Pair(false, null) -> { log.info("Event stream active ping") }
                Pair(true, null) -> { log.warn("Received completed"); return }
                else -> { throw responseObserver.error!! }
            } as Unit
        }
    }

    // Extract all scopes from an event batch
    private fun EventBatch.scopes(): List<ScopeEvent> =
        this.events.map { event ->
            ScopeEvent(
                event.txHash,
                event.findScope(),
                event.eventType.toEventType()
            )
         }

    // Find the scope in an event.
    private fun StreamEvent.findScope(): Scope = this.attributes.find { it.key == "scope_addr" }?.let {
        provenanceGrpcService.retrieveScope(it.value!!.removeSurrounding("\""))
    } ?: throw IllegalStateException("Event does not contain a scope")

    // Parse a scope from an attribute value.
    // We only call this after we find a matching "key" so it should be safe to convert to non nullable
    private fun Attribute.toScope(): Scope =
        Scope.parseFrom(this.value!!.base64Decode())

    // Queue a batch of scopes for indexing.
    fun queueIndexScopes(blockHeight: Long, events: List<ScopeEvent>) = timed("ScopeStream_indexScopes_${events.size}") {
        P8eMDC.set(blockHeight.toBlockHeight(), clear = true)
            .set(events.map { it.txHash }.toTransactionHashes())

        log.info("Received event stream block!")

        transaction {
            val uuids = IndexScopeRecord.batchInsert(blockHeight, events).flatMap { it.value }.toSet()
            uuids.forEach {
                // TODO: write a test for and possibly fix the case where multiple parties on multiparty contract share same node, but have different index names
                // may need to index each separately to ensure both indexes populated https://github.com/FigureTechnologies/p8e/pull/444/files#r557465484
                EnvelopeRecord.findByExecutionUuid(it.executionUuid).forEachIndexed { i, envelope ->
                    eventService.submitEvent(
                        P8eEvent.newBuilder()
                            .setEvent(if (i == 0) Event.SCOPE_INDEX else Event.SCOPE_INDEX_FRAGMENT)
                            .setMessage(it.indexScopeUuid.toProtoUuidProv().toByteString())
                            .build(),
                        envelope.uuid.value
                    )
                }
            }

            events.map { it.txHash }
                .toSet()
                .also { txHashes -> log.debug("Received the following TXs $txHashes at height $blockHeight") }
                .forEach { txHash -> TransactionStatusRecord.setSuccess(txHash) }

            // Mark that we've stored up to the given block height for indexing.
            EventStreamRecord.update(eventStreamId, blockHeight)

            events.forEach {
                ChaincodeInvokeService.freeScope(it.scope.uuid.toUuidProv())
            }
        }
    }
}
