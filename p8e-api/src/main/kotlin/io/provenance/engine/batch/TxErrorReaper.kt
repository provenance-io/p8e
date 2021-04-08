package io.provenance.engine.index

import io.p8e.proto.ContractScope
import io.p8e.proto.ContractScope.Scope
import io.p8e.util.base64Decode
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.config.EventStreamProperties
import io.provenance.engine.domain.EventStreamRecord
import io.provenance.engine.domain.GetTxResult
import io.provenance.engine.domain.TransactionStatusRecord
import io.provenance.engine.domain.TxResult
import io.provenance.engine.service.TransactionNotFoundError
import io.provenance.engine.service.TransactionQueryService
import org.jetbrains.exposed.sql.transactions.transaction
import io.provenance.engine.service.TransactionStatusService
import io.provenance.engine.stream.ScopeStream
import io.provenance.engine.stream.domain.Attribute
import io.provenance.engine.stream.domain.Event
import io.provenance.p8e.shared.index.ScopeEvent
import io.provenance.p8e.shared.index.isScopeEventType
import io.provenance.p8e.shared.index.toEventType
import io.provenance.p8e.shared.util.P8eMDC
import io.provenance.p8e.shared.util.toBlockHeight
import io.provenance.p8e.shared.util.toTransactionHashes
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime

@Component
class TxErrorReaper(
    private val transactionStatusService: TransactionStatusService,
    private val transactionQueryService: TransactionQueryService,
    private val scopeStream: ScopeStream,
    eventStreamProperties: EventStreamProperties
) {
    private val log = logger()

    private val eventStreamId = java.util.UUID.fromString(eventStreamProperties.id)
    private val epochHeight = eventStreamProperties.epoch.toLong()

    private val eventStreamBlockHeight
        get() = transaction { EventStreamRecord.findById(eventStreamId)?.lastBlockHeight ?: epochHeight }

    @Scheduled(fixedDelay = 60_000)
    fun pollExpiredTransactions() = eventStreamBlockHeight.let { latestBlockHeight ->
        transaction {
            TransactionStatusRecord.getExpiredForUpdate().toList().also {
                OffsetDateTime.now().also { now -> it.forEach { txStatus -> txStatus.updated = now } }
            }
        }.forEach {
            try {
                P8eMDC.set(it.transactionHash.value.toTransactionHashes(), clear = true)
                transactionQueryService.fetchTransaction(it.transactionHash.value).let { transactionStatus ->
                    if (transactionStatus.isErrored()) {
                        transaction { transactionStatusService.setError(it, transactionStatus.getError()) }
                    } else if (transactionStatus.isSuccessful()) {
                        P8eMDC.set(transactionStatus.height.toBlockHeight())
                        val blockHeight = transactionStatus.height
                        if (blockHeight <= latestBlockHeight) { // EventStream is past this height, need to process manually
                            transactionStatus.scopeEvents()
                                .also { events -> log.error("indexing ${events.size} events missed by eventStream") }
                                .map { event -> event.toScopeEvent() }
                                .also { events -> scopeStream.queueIndexScopes(blockHeight, events) }
                        }
                    }
                }
            } catch (e: TransactionNotFoundError) {
                log.warn("Retrying transaction not found in mempool with hash ${it.transactionHash.value}")
                transaction { transactionStatusService.retryDead(it, e.message) }
            } catch (t: Throwable) {
                log.warn("Error processing expired transaction", t)
            }
        }
    }

    private fun GetTxResult.isErrored() = txResult?.code != null && txResult?.code > 0

    private fun GetTxResult.isSuccessful() = txResult?.code == 0

    private fun GetTxResult.getError() = txResult?.log ?: "Unknown Error"

    private fun GetTxResult.scopeEvents(): List<Event> = txResult?.events?.filter { it.type.isScopeEventType() } ?: emptyList()

    private fun Event.findTxHash(): String = attributes.find { it.key == "tx_hash" }?.value
        ?: throw IllegalStateException("Event does not contain a transaction hash")

    private fun Event.findScope(): Scope = attributes.find { it.key == "scope" }?.toScope()
        ?: throw IllegalStateException("Event does not contain a scope")

    private fun Event.toScopeEvent(): ScopeEvent = ScopeEvent(findTxHash(), findScope(), type.toEventType())

    // We only call this after we find a matching "key" so it should be safe to convert to non nullable
    private fun Attribute.toScope(): Scope = value!!.base64Decode().let { Scope.parseFrom(it) }
        ?: throw IllegalStateException("Event attribute does not contain a scope")
}
