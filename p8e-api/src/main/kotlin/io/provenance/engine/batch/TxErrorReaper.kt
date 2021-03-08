package io.provenance.engine.index

import io.p8e.proto.ContractScope
import io.p8e.proto.ContractScope.Scope
import io.p8e.util.base64Decode
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.config.EventStreamProperties
import io.provenance.engine.domain.EventStreamRecord
import io.provenance.engine.domain.TransactionStatusRecord
import io.provenance.pbc.clients.*
import org.jetbrains.exposed.sql.transactions.transaction
import io.provenance.engine.service.TransactionStatusService
import io.provenance.engine.stream.ScopeStream
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
    private val sc: SimpleClient,
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
                sc.fetchTx(it.transactionHash.value).let { transactionStatus ->
                    if (transactionStatus.isErrored()) {
                        transaction { transactionStatusService.setError(it, transactionStatus.getError()) }
                    } else {
                        P8eMDC.set(transactionStatus.height.toLong().toBlockHeight())
                        val blockHeight = transactionStatus.height.toLong()
                        if (blockHeight <= latestBlockHeight) { // EventStream is past this height, need to process manually
                            transactionStatus.events()
                                ?.also { events -> log.error("indexing ${events.size} events missed by eventStream") }
                                ?.map { event -> event.toScopeEvent() }
                                ?.also { events -> scopeStream.queueIndexScopes(blockHeight, events) }
                        }
                    }
                }
            } catch (e: CosmosRemoteInvocationException) {
                if (e.status == HttpStatus.NOT_FOUND.value()) {
                    log.warn("Retrying transaction not found in mempool with hash ${it.transactionHash.value}")
                    transaction { transactionStatusService.retryDead(it, e.message) }
                } else {
                    log.error("Error fetching status for tx hash ${it.transactionHash.value}", e)
                }
            } catch (t: Throwable) {
                log.warn("Error processing expired transaction", t)
            }
        }
    }

    private fun TxQuery.isErrored() = (code ?: 0) > 0

    private fun TxQuery.getError() = logs?.filter { it.log.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString("; ") { it.log } ?: raw_log

    private fun TxQuery.events(): List<StdTxEvent>? = logs?.flatMap { it.events.filter { it.type.isScopeEventType() } }

    private fun StdTxEvent.findTxHash(): String = attributes.find { it.key == "tx_hash" }?.value
        ?: throw IllegalStateException("Event does not contain a transaction hash")

    private fun StdTxEvent.findScope(): Scope = attributes.find { it.key == "scope" }?.toScope()
        ?: throw IllegalStateException("Event does not contain a scope")

    private fun StdTxEvent.toScopeEvent(): ScopeEvent = ScopeEvent(findTxHash(), findScope(), type.toEventType())

    private fun StdTxEventAttribute.toScope(): Scope = value?.base64Decode().let { Scope.parseFrom(it) }
        ?: throw IllegalStateException("Event attribute does not contain a scope")
}
