package io.provenance.engine.index

import cosmos.base.abci.v1beta1.Abci
import io.p8e.proto.ContractScope.Scope
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.config.EventStreamProperties
import io.provenance.engine.domain.EventStreamRecord
import io.provenance.engine.domain.TransactionStatusRecord
import io.provenance.engine.service.ProvenanceGrpcService
import io.provenance.engine.service.TransactionNotFoundError
import io.provenance.engine.service.TransactionQueryService
import org.jetbrains.exposed.sql.transactions.transaction
import io.provenance.engine.service.TransactionStatusService
import io.provenance.engine.stream.ScopeStream
import io.provenance.p8e.shared.index.ScopeEvent
import io.provenance.p8e.shared.index.isScopeEventType
import io.provenance.p8e.shared.index.toEventType
import io.provenance.p8e.shared.util.P8eMDC
import io.provenance.p8e.shared.util.toBlockHeight
import io.provenance.p8e.shared.util.toTransactionHashes
import java.time.OffsetDateTime

@Component
class TxErrorReaper(
    private val transactionStatusService: TransactionStatusService,
    private val transactionQueryService: TransactionQueryService,
    private val scopeStream: ScopeStream,
    eventStreamProperties: EventStreamProperties,
    private val provenanceGrpcService: ProvenanceGrpcService,
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
                                .also { events -> log.warn("indexing ${events.size} events missed by eventStream") }
                                .map { event -> event.toScopeEvent(it.transactionHash.value) }
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

    private fun Abci.TxResponse.isErrored() = code > 0

    private fun Abci.TxResponse.isSuccessful() = height > 0 && code == 0

    private fun  Abci.TxResponse.getError() = rawLog ?: "Unknown Error"

    private fun Abci.TxResponse.scopeEvents(): List<Abci.StringEvent> = logsList.flatMap { it.eventsList }.filter { it.type.isScopeEventType() }

    private fun Abci.StringEvent.findScope(): Scope = attributesList.find { it.key == "scope_addr" }?.let {
        provenanceGrpcService.retrieveScope(it.value!!.removeSurrounding("\""))
    } ?: throw IllegalStateException("Event does not contain a scope")

    private fun Abci.StringEvent.toScopeEvent(txHash: String): ScopeEvent = ScopeEvent(txHash, findScope(), type.toEventType())
}
