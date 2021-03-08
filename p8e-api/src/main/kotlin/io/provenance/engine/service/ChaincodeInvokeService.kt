package io.provenance.engine.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.Contracts
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toUuidProv
import io.p8e.util.configureProvenance
import io.provenance.engine.config.ChaincodeProperties
import io.provenance.engine.crypto.Account
import io.provenance.engine.domain.TransactionStatusRecord
import io.provenance.p8e.shared.domain.ContractTxResult
import io.provenance.pbc.clients.*
import io.provenance.pbc.clients.p8e.addP8EContractSpec
import io.provenance.pbc.clients.p8e.changeScopeOwnership
import io.provenance.pbc.clients.p8e.memorializeP8EContract
import io.provenance.pbc.clients.tx.BatchTx
import io.provenance.pbc.clients.tx.TxPreparer
import io.provenance.pbc.clients.tx.batch
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@Component
class ChaincodeInvokeService(
    private val accountProvider: Account,
    private val transactionStatusService: TransactionStatusService,
    private val sc: SimpleClient,
    private val chaincodeProperties: ChaincodeProperties
) : IChaincodeInvokeService {
    private val log = logger()

    private val objectMapper = ObjectMapper().configureProvenance()
    private val indexRegex = "^.*message index: (\\d+).*$".toRegex()

    private val queue = ConcurrentHashMap<UUID, BlockchainTransaction>()

    init {
        thread(isDaemon = true, name = "bc-tx-batch") {
            memorializeBatchTx()
        }
    }

    private data class BlockchainTransaction(val txPreparer: TxPreparer, val future: CompletableFuture<ContractTxResult>, val executionUuid: UUID, var attempts: Int = 0)

    fun memorializeBatchTx() {
        log.info("Starting bc-tx-batch thread")

        var emptyIterations = 0

        while(true) {
            Thread.sleep(chaincodeProperties.emptyIterationBackoffMS.toLong())

            val batch = mutableMapOf<UUID, BlockchainTransaction>()

            while (batch.size < chaincodeProperties.txBatchSize) {
                val it = queue.iterator()
                if (it.hasNext()) {
                    val e = it.next()
                    batch.put(e.key, e.value)
                    it.remove()
                } else {
                    break
                }
            }

            // Skip the rest of the loop if there are no transactions to execute.
            if (batch.size == 0) {
                log.debug("No batch available, waiting...")
                emptyIterations = Math.min(emptyIterations + 1, 40)
                continue
            }

            // Rest emptyIterations to speed up the batch cycle now that we're rolling again.
            log.info("Sending batch ${batch.size} ${batch.keys}")
            emptyIterations = 0

            // We need to store the batch indexes to map to error messages.
            val batchIndex = mutableMapOf<Int, UUID>()

            // Create a new batch of transactions.
            val tx = batch {
                var index = 0;
                batch.map {
                    batchIndex.put(index++, it.key)

                    // Note that we've attempted this transaction before.
                    it.value.attempts++

                    prepare { it.value.txPreparer }
                }
            }

            val transactionExecutionUuids = batch.map { it.value.executionUuid }
            try {
                // Send the transactions to the blockchain.
                val resp = synchronized(sc) { scBatchTx(tx) }

                // We successfully received a transaction hash so we can assume these will complete shortly.
                transaction { TransactionStatusRecord.insert(resp.txhash, transactionExecutionUuids) }
                batch.forEach {
                    it.value.future.complete(ContractTxResult(it.key.toString()))
                }
            } catch (e: PBTransactionResultException) {
                try {
                    handleTransactionException(e, batch, transactionExecutionUuids, batchIndex)
                } catch (e: Exception) {
                    log.error("Handle typed transaction error - ${batch.keys} need _manual_ intervention", e)
                }
            } catch (t: Throwable) {
                try {
                    decrementSequenceNumber()
                    log.warn("Unexpected chain execution error", t)
                    val executionUuids = batch.map {
                        it.value.future.completeExceptionally(t)

                        it.value.executionUuid
                    }

                    transaction { transactionStatusService.setEnvelopeErrors(t.toString(), executionUuids) }
                } catch (e: Exception) {
                    log.error("Handle generic transaction error - ${batch.keys} need _manual_ intervention", e)
                }
            }
        }
    }

    private fun handleTransactionException(e: PBTransactionResultException, batch: MutableMap<UUID, BlockchainTransaction>, transactionExecutionUuids: List<UUID>, batchIndex: MutableMap<Int, UUID>) {
        decrementSequenceNumber()
        var executionUuidsToFail = transactionExecutionUuids // default to failing all envelopes, unless a specific envelope can be identified or certain envelopes can be retried

        val signatureVerificationFailed = e.reply.code == 4
        val retryable = signatureVerificationFailed ||
                e.reply.raw_log.contains("txn invalid: rmi submit") // can be false negative due to timeout... I don't think we are actually catching this as it is a PBTransactionException, not PBTransactionResultException

        val match = indexRegex.find(e.reply.raw_log)?.let {
            if (it.groupValues.size == 2)
                it.groupValues[1]
            else
                null
        }
        log.info("Found index ${match}")

        val errorMessage = "${e.reply.raw_log} - ${e.cause?.message}"

        if (match == null) {
            var printedException = false
            if (retryable) {
                executionUuidsToFail = batch.filter {
                    it.value.attempts > 4
                }.map {
                    if (!printedException) {
                        log.warn("Exception couldn't be resolved", e)
                        printedException = true
                    }

                    log.warn("Exceeded max retry attempts ${it.key}")
                    batch.remove(it.key)!!.future.completeExceptionally(IllegalStateException(errorMessage))

                    it.value.executionUuid
                }

                // Because this could be a sequencing issue, let's wait a full block cut cycle before we retry.
                log.info("Waiting for block cut...")
                for (i in 1..100) {
                    Thread.sleep(2500)
                    val blockHasBeenCut = synchronized(sc) { sc.blockHasBeenCut() }
                    if (blockHasBeenCut) {
                        log.info("block cut detected")
                        break
                    }
                }

                log.warn("Retrying due to ${e.reply.raw_log}")
                batch.forEach {
                    queue.put(it.key, it.value)
                }
            } else {
                batch.forEach {
                    it.value.future.completeExceptionally(IllegalStateException(errorMessage))
                }
            }
        } else {
            // Ship the error back for the bad index.
            val erroredUuid = batchIndex[match!!.toInt()]
            batch.remove(erroredUuid)!!.also {
                executionUuidsToFail = listOf(it.executionUuid)
            }.future.completeExceptionally(IllegalStateException(errorMessage))

            // Resend the rest of the contracts for execution.
            batch.forEach {
                queue.put(it.key, it.value)
            }
        }

        transaction {
            TransactionStatusRecord.insert(e.reply.txhash, transactionExecutionUuids).let {
                transactionStatusService.setError(it, errorMessage, executionUuidsToFail)
            }
        }
    }

    private fun parseTxReply(reply: SubmitStdTxReply): Map<UUID, ContractTxResult> {
        val results = mutableMapOf<UUID, ContractTxResult>()

        if(reply.logs.isNullOrEmpty()) {
            log.info("batch made it to mempool with txhash = ${reply.txhash}")
        }

        reply.logs?.forEach { txReply ->
            val result = ContractTxResult();
            result.errorMsg = txReply.log

            txReply.events.forEach { txEvent ->
                log.info("Found message type ${txEvent.type}")
                txEvent.attributes.forEach {
                    log.info("\tk: ${it.key} v: ${it.value}")
                }

                val attrs = txEvent.attributes
                when (txEvent.type) {
                    "scope_updated", "scope_created" -> {
                        result.scopeId = attrs.firstOrNull { it.key == "scope_id" }?.value
                        result.scope = attrs.firstOrNull { it.key == "scope" }?.value
                    } else -> {

                    }
                }
            }

            if (result.scopeId != null)
                results.put(UUID.fromString(result.scopeId), result)
        }

        return results
    }

    /**
     * Memorialize a contract envelope
     *
     * @param [env] the contract to memorialize with env wrapper
     */
    fun memorializeContract(env: Envelope): CompletableFuture<ContractTxResult> {
        env.let { com.google.protobuf.util.JsonFormat.printer().print(it) }

        val future = CompletableFuture<ContractTxResult>()

        // TODO - laugh at this loudly and then realize that you are probably the one that has to fix it.
        val waitTime = 1000L
        var iterations = 0
        while (queue.putIfAbsent(env.ref.scopeUuid.toUuidProv(),
                BlockchainTransaction(
                    synchronized(sc) { // TODO - Replace after service account setup
                        when (env.contract.type!!) {
                            Contracts.ContractType.FACT_BASED -> sc.contracts.memorializeP8EContract(env)
                            Contracts.ContractType.CHANGE_SCOPE -> sc.contracts.changeScopeOwnership(env)
                            Contracts.ContractType.UNRECOGNIZED -> throw IllegalStateException("Unrecognized contract type of ${env.contract.typeValue} for envelope ${env.executionUuid.value}")
                        }
                    },
                    future,
                    env.executionUuid.toUuidProv()
                )
            ) != null) {
                log.info("Stuck in makeshift block for scope: ${env.ref.scopeUuid.value}")
                Thread.sleep(waitTime)
                iterations++
        }

        // Log out how long some memorializes are waiting.
        if (iterations > 0)
            log.info("scope: ${env.ref.scopeUuid.value} waited ${waitTime * iterations}ms before being queued.")

        return future
    }

    /**
     * Add a contract spec.
     *
     * @param [specs] the specs to load
     */
    fun addContractSpecs(specList: List<ContractSpec>) {
        log.info("received a set of contract specs: ${specList.size}")
        try {
            synchronized(sc) { // TODO - Replace after service account setup
                batch {
                    specList.map {
                        prepare { sc.contractSpecs.addP8EContractSpec(it) }
                    }
                }.also {
                    scBatchTx(it).also {
                        log.info("batch made it to mempool with txhash = ${it.txhash}")
                    }
                }
            }
        } catch(e: Throwable) {
            log.warn("failed to add contract spec: ${e.message}")
            throw e
        }
    }

    /**
     * Send batched transaction to pbc
     * @param [tx] batch of transactions
     *
     * StdTxMode.sync == like tcp (guaranteed it made it to the pool, might still fail if its invalid)
     * StdTxMode.async == like udp (guaranteed it made it to the node, might be in the pool, might still fail, might pass, :shrug: )
     * StdTxMode.block == like rest call (sit and block until the block is cut that it is in, txn runs, and has worked or not).
     */
    fun scBatchTx(tx: BatchTx): SubmitStdTxReply {
        val accountInfo = sc.getAccountInfo()
        val estimate = sc.estimateTx(batch = tx, gasAdjustment = "1.4", fromAccountInfo = accountInfo)

        return sc.batchTx(
            gas = estimate.total.toString(),
            fees = listOf(estimate.fees coins Denom.vspn),
            batch = tx,
            mode = StdTxMode.sync,
            sequenceNumberOffset = accountInfo.getAndIncrementSequenceOffset(),
            fromAccountInfo = accountInfo
        )
    }

    private fun SimpleClient.getAccountInfo(): AccountInfo = fetchAccountDetails(accountProvider.bech32Address()).result.value

    private fun SimpleClient.blockHasBeenCut(): Boolean = sc.getAccountInfo().blockHasBeenCut()

    private var sequenceNumberAndOffset = 0 to 0 // todo: change left back to uint once https://github.com/FasterXML/jackson-module-kotlin/issues/396 fixed

    private fun AccountInfo.getAndIncrementSequenceOffset(): Int {
        if (blockHasBeenCut()) {
            sequenceNumberAndOffset = sequence to 0
        }

        return sequenceNumberAndOffset.second.also {
            sequenceNumberAndOffset = sequence to it + 1
        }
    }

    private fun AccountInfo.blockHasBeenCut(): Boolean = (sequence > sequenceNumberAndOffset.first) || sequenceNumberAndOffset.second == 0

    private fun decrementSequenceNumber() {
        sequenceNumberAndOffset = sequenceNumberAndOffset.first to Math.max(sequenceNumberAndOffset.second - 1, 0)
    }
}
