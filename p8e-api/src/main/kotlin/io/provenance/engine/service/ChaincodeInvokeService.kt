package io.provenance.engine.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import cosmos.base.abci.v1beta1.Abci
import cosmos.tx.v1beta1.ServiceOuterClass.BroadcastTxResponse
import cosmos.tx.v1beta1.TxOuterClass.TxBody
import io.grpc.StatusRuntimeException
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractSpecs
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.Contracts
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.config.ChaincodeProperties
import io.provenance.engine.crypto.Account
import io.provenance.engine.domain.TransactionStatusRecord
import io.provenance.engine.util.toProv
import io.provenance.metadata.v1.Description
import io.provenance.metadata.v1.MsgP8eMemorializeContractRequest
import io.provenance.metadata.v1.MsgWriteP8eContractSpecRequest
import io.provenance.metadata.v1.MsgWriteScopeSpecificationRequest
import io.provenance.metadata.v1.ScopeSpecification
import io.provenance.p8e.shared.domain.ContractTxResult
import io.provenance.p8e.shared.domain.ScopeSpecificationRecord
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

const val SIGNATURE_VERIFICATION_FAILED = 4

data class ContractRequestWrapper(
    val executionUuid: UUID,
    val request: MsgP8eMemorializeContractRequest,
    val future: CompletableFuture<ContractTxResult>,
)

@Component
class ChaincodeInvokeService(
    private val accountProvider: Account,
    private val transactionStatusService: TransactionStatusService,
    private val chaincodeProperties: ChaincodeProperties,
    private val provenanceGrpc: ProvenanceGrpcService,
) : IChaincodeInvokeService {

    companion object {
        private val log = logger()

        private val blockScopeIds = ConcurrentHashMap.newKeySet<String>()
        private val scopeLockHeights = ConcurrentHashMap<String, Long>()
        private var currentBlockHeight = 0L

        private fun scopeLocked(scopeUuid: String): Boolean = blockScopeIds.contains(scopeUuid)

        private fun lockScope(scopeUuid: String) {
            require(!scopeLocked(scopeUuid)) { "attempted to lock scope that was already locked [scopeUuid = $scopeUuid]" }

            blockScopeIds.add(scopeUuid)
            scopeLockHeights[scopeUuid] = currentBlockHeight
        }

        fun unlockScope(scopeUuid: String) {
            blockScopeIds.remove(scopeUuid)
            scopeLockHeights.remove(scopeUuid)
        }

        private fun logStaleScopeLocks() {
            scopeLockHeights.filter {
                it.value < currentBlockHeight - 10
            }.forEach {
                log.error("Scope ${it.key} has been locked for > 10 blocks")
                scopeLockHeights.remove(it.key) // we only want to log this error once per scope
            }
        }

        private val MEMORIALIZE_MESSAGE_TYPEURL = MsgP8eMemorializeContractRequest.getDefaultInstance().toAny().typeUrl
    }

    private val objectMapper = ObjectMapper().configureProvenance()
    private val indexRegex = "^.*message index: (\\d+).*$".toRegex()

    private val accountInfo = provenanceGrpc.accountInfo()

    // Optional gas multiplier tracking
    private var gasMultiplierResetAt = OffsetDateTime.now()
    private var gasMultiplierDailyCount = 0
        get() {
            if (gasMultiplierResetAt.plusDays(1) < OffsetDateTime.now()) {
                log.info("resetting gasMultiplier daily count to 0")
                field = 0
                gasMultiplierResetAt = gasMultiplierResetAt.plusDays(1)
            }
            return field
        }

    // private val queue = ConcurrentHashMap<UUID, BlockchainTransaction>()

    // thread safe queue because this spans the worker thread and the enqueue
    private val queue = LinkedBlockingQueue<ContractRequestWrapper>(1_000)

    // non-thread safe data structures that are only used within the worker thread
    private val batch = mutableListOf<ContractRequestWrapper>()
    private val priorityScopeBacklog = HashMap<String, LinkedList<ContractRequestWrapper>>()

    // helper extensions for batch management
    private val MutableList<ContractRequestWrapper>.executionUuids: List<UUID> get() = map { it.executionUuid }
    private fun MutableList<ContractRequestWrapper>.removeContract(contract: ContractRequestWrapper) {
        remove(contract)
        attemptTracker.remove(contract)
    }

    private val attemptTracker = HashMap<ContractRequestWrapper, Int>()
    private var ContractRequestWrapper.attempts: Int
        get() = attemptTracker[this] ?: 0
        set(attempt: Int) { attemptTracker[this] = attempt }

    init {
        thread(isDaemon = true, name = "bc-tx-batch") {
            while (true) {
                try {
                    memorializeBatchTx()
                } catch (t: Throwable) {
                    log.error("Unexpected error in memorializeBatchTx, restarting...", t)
                }
            }
        }
    }

    fun memorializeBatchTx() {
        log.info("Starting bc-tx-batch thread")

        while(true) {
            try {
                provenanceGrpc.getLatestBlock()
                    .takeIf { it.block.header.height > currentBlockHeight }
                    ?.let {
                        currentBlockHeight = it.block.header.height

                        logStaleScopeLocks()
                    }
            } catch (t: Throwable) {
                log.warn("Received error when fetching latest block, waiting 1s before trying again", t)
                Thread.sleep(1000);
                continue;
            }


            // attempt to load the batch with scopes that were previously passed on due to not
                // wanting to send the same scope in the same block
            while (batch.size < chaincodeProperties.txBatchSize) {
                // filter the backlog for scopes that aren't in the block and then pick a random
                // scope and insert it into the block
                priorityScopeBacklog.filterKeys { !scopeLocked(it) }
                    .keys
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?.let { keyList ->
                        val key = keyList.get(Random.nextInt(keyList.size))
                        val messages = priorityScopeBacklog.getValue(key)
                        val message = messages.pop()

                        if (messages.isEmpty()) {
                            log.debug("removing scope $key from backlog as its list is now empty")

                            priorityScopeBacklog.remove(key)
                        }

                        log.debug("adding ${message.request.scopeId} to batch")
                        lockScope(message.request.scopeId)
                        batch.add(message)
                    } ?: break
            }

            while (batch.size < chaincodeProperties.txBatchSize) {
                queue.poll(chaincodeProperties.emptyIterationBackoffMS.toLong(), TimeUnit.MILLISECONDS)?.let { message ->
                    if (!scopeLocked(message.request.scopeId)) {
                        log.debug("adding ${message.request.scopeId} to batch")

                        lockScope(message.request.scopeId)
                        batch.add(message)
                    } else {
                        val list = priorityScopeBacklog.getOrDefault(message.request.scopeId, LinkedList())
                            .also { if (it.isEmpty()) priorityScopeBacklog.put(message.request.scopeId, it) }

                        log.debug("adding ${message.request.scopeId} to backlog with size = ${list.size + 1}")

                        list.add(message)
                    }
                } ?: break
            }

            // Skip the rest of the loop if there are no transactions to execute.
            if (batch.size == 0) {
                log.debug("No batch available, waiting...")
                log.debug("Internal structures\nblockScopeIds: $blockScopeIds\npriorityFutureScopeToQueue: ${priorityScopeBacklog.entries.map { e -> "${e.key} => ${e.value.size}"}}")

                continue
            }

            log.info("Sending batch with size: ${batch.size} ${batch.map { it.request.scopeId }} total backlog queue size: ${priorityScopeBacklog.values.fold(0) { acc, list ->  acc + list.size }}")
            log.debug("Internal structures\nblockScopeIds: $blockScopeIds\npriorityFutureScopeToQueue: ${priorityScopeBacklog.entries.map { e -> "${e.key} => ${e.value.size}"}}")

            try {
                log.info("currentBlockHeight = $currentBlockHeight | timeout = ${currentBlockHeight + chaincodeProperties.blockHeightTimeoutInterval}")
                val txBody = batch.map {
                    it.attempts++
                    it.request
                }.toTxBody(currentBlockHeight + chaincodeProperties.blockHeightTimeoutInterval)

                // Send the transactions to the blockchain.
                val resp = synchronized(provenanceGrpc) { batchTx(txBody) }

                if (resp.txResponse.code != 0) {
                    // adding extra raw logging during exceptional cases so that we can see what typical responses look like while this interface is new
                    log.info("Abci.TxResponse from chain ${resp.txResponse}")

                    try {
                        handleTransactionException(resp.txResponse)
                    } catch (e: Exception) {
                        log.error("Handle typed transaction error - ${batch.executionUuids} need _manual_ intervention", e)
//                            log.error("Handle typed transaction error - ${batch.map { resp.uuid }} need _manual_ intervention")
                    }
                } else {
                    // We successfully received a transaction hash so we can assume these will complete shortly.
                    transaction { TransactionStatusRecord.insert(resp.txResponse.txhash, batch.executionUuids) }
                    batch.map {
                        attemptTracker.remove(it)
                        it.future.complete(ContractTxResult(it.request.scopeId))
                    }
                    log.info("batch made it to mempool with txhash = ${resp.txResponse.txhash}")

                    batch.clear()
                }
            } catch (t: Throwable) {
                // TODO I'm pretty sure since this is now grpc the only things that would be caught in here
                    // are network related problems and we should probably retry instead of fail but
                        // it doesn't harm much until we know more to push the retry onto the user
                try {
                    // TODO rework this
                    decrementSequenceNumber()
                    log.warn("Unexpected chain execution error", t)
                    val errorMessage = t.message ?: "Unexpected chain execution error"

                    val retryable = t.message?.contains("account sequence mismatch") == true
                    val matchIndex = t.message?.matchIndex()

                    val executionUuidsToFail = when {
                        matchIndex != null -> {
                            listOf(rejectContractByIndex(matchIndex, errorMessage))
                        }
                        retryable -> {
                            handleBatchRetry(errorMessage)
                        }
                        else -> { // fail the whole batch
                            batch.map {
                                it.future.completeExceptionally(t)
                                unlockScope(it.request.scopeId)
                                it.executionUuid
                            }.also {
                                batch.clear()
                            }
                        }
                    }

                    transaction { transactionStatusService.setEnvelopeErrors(t.toString(), executionUuidsToFail) }
                } catch (e: Exception) {
                    log.error("Handle generic transaction error - ${batch.executionUuids} need _manual_ intervention", e)
                }
            }
        }
    }

     private fun handleTransactionException(response: Abci.TxResponse) {
         decrementSequenceNumber()

         // default to failing all envelopes, unless a specific envelope can be identified or certain envelopes can be retried
         var executionUuidsToFail = batch.executionUuids
         val txExecutionUuids = batch.executionUuids

         val retryable = response.code == SIGNATURE_VERIFICATION_FAILED

         val matchIndex = response.rawLog.matchIndex()
         log.info("Found index ${matchIndex}")

         val errorMessage = "${response.code} - ${response.rawLog}"

         if (matchIndex == null) {
             if (retryable) {
                 executionUuidsToFail = handleBatchRetry(errorMessage)
             } else {
                 // fail whole batch
                 batch.forEach {
                     it.future.completeExceptionally(IllegalStateException(errorMessage))
                     unlockScope(it.request.scopeId)
                 }
                 batch.clear()
             }
         } else {
             // Ship the error back for the bad index.
             executionUuidsToFail = listOf(rejectContractByIndex(matchIndex, errorMessage))
         }

         transaction {
             TransactionStatusRecord.insert(response.txhash, txExecutionUuids).let {
                 transactionStatusService.setError(it, errorMessage, executionUuidsToFail)
             }
         }
     }

    /**
     * Parse an error message to identify the index of the contract that caused the error
     * @return the match index, or null if no specific match is found
     */
    private fun String.matchIndex(): Int? = indexRegex.find(this)?.let {
        if (it.groupValues.size == 2)
            it.groupValues[1].toInt()
        else
            null
    }

    /**
     * Reject a specific contract within the batch based on its index in the list
     * @param index: the index to reject
     * @param errorMessage: the error message to supply for the rejected contract
     * @return the execution uuid of the rejected contract, should be shipped as a failure
     */
    private fun rejectContractByIndex(index: Int, errorMessage: String): UUID {
        val erroredContract = batch[index]
        batch.removeContract(erroredContract)
        erroredContract.future.completeExceptionally(IllegalStateException(errorMessage))
        unlockScope(erroredContract.request.scopeId)
        return erroredContract.executionUuid
    }

    /**
     * Reject any contracts from batch which have been retried at least 4 times
     * @param errorMessage: the error message to supply for rejected contracts in batch
     * @return the execution uuids that were rejected and should be shipped as failures
      */
    private fun handleBatchRetry(errorMessage: String): List<UUID> {
        var printedException = false
        val executionUuidsToFail = batch.filter { it.attempts > 4 }
            .map {
                if (!printedException) {
                    log.warn("Exception couldn't be resolved: $errorMessage")
                    printedException = true
                }

                log.warn("Exceeded max retry attempts for execution: ${it.executionUuid}")
                batch.removeContract(it)
                it.future.completeExceptionally(IllegalStateException(errorMessage))

                unlockScope(it.request.scopeId)

                it.executionUuid
            }

        // Because this could be a sequencing issue, let's wait a full block cut cycle before we retry.
        waitForBlockCut()

        if (batch.isNotEmpty()) {
            log.warn("Retrying due to ${errorMessage}")
        }

        return executionUuidsToFail
    }

    // TODO refactor these functions

    fun offer(env: Envelope): Future<ContractTxResult> {
        val msg = when (env.contract.type!!) {
            Contracts.ContractType.FACT_BASED -> env.toProv(accountProvider.bech32Address())
            // TODO add this back when implemented
            Contracts.ContractType.CHANGE_SCOPE,
            Contracts.ContractType.UNRECOGNIZED -> throw IllegalStateException("Unrecognized contract type of ${env.contract.typeValue} for envelope ${env.executionUuid.value}")
        }

        val future = CompletableFuture<ContractTxResult>()

        // TODO what to do when this offer fails?
        queue.offer(ContractRequestWrapper(env.executionUuid.toUuidProv(), msg, future))

        return future
    }

    /**
     * Add contract specs.
     *
     * @param [scopeSpecs] the scope specs to load
     * @param [contractSpecs] the contract specs to load
     */
    fun addContractSpecs(
        scopeSpecs: Collection<ScopeSpecificationRecord>,
        scopeSpecIdToContractSpecHashes: Map<UUID, Collection<ByteString>>,
        contractSpecs: List<ContractSpec>,
    ) {
        val logPrefix = "[addContractSpecs]"
        log.info("$logPrefix received a set of contract specs: ${contractSpecs.size} and scope specs: ${scopeSpecs.size}")

        val owners = listOf(accountProvider.bech32Address())

        try {
            val scopeSpecTx = scopeSpecs.map {
                MsgWriteScopeSpecificationRequest.newBuilder()
                    .setSpecUuid(it.id.value.toString())
                    .setSpecification(ScopeSpecification.newBuilder()
                        .setDescription(Description.newBuilder()
                            .setName(it.name)
                            .setDescription(it.description)
                            .setWebsiteUrl(it.websiteUrl)
                            .setIconUrl(it.iconUrl)
                            .build()
                        )
                        .addAllPartiesInvolved(it.partiesInvolved.map { p -> ContractSpecs.PartyType.valueOf(p).toProv() })
                        .addAllOwnerAddresses(owners)
                        .addAllContractSpecIds(scopeSpecIdToContractSpecHashes.getOrDefault(it.id.value, listOf()))
                        .build()
                    )
                    .addAllSigners(owners)
                    .build()
            }
            val contractSpecTx = contractSpecs.map {
                MsgWriteP8eContractSpecRequest.newBuilder()
                    .setContractspec(it.toProv())
                    .addAllSigners(owners)
                    .build()
            }
            val contractSpecHashes = contractSpecTx.chunked(chaincodeProperties.contractSpecTxBatchSize).map { messages ->
                log.info("$logPrefix sending batch of ${messages.size} contract spec messages")
                val txBody = messages.toTxBody(provenanceGrpc.getLatestBlock().block.header.height + chaincodeProperties.blockHeightTimeoutInterval)

                synchronized(provenanceGrpc) {
                    batchTx(txBody, applyMultiplier = false).let {
                        if (it.txResponse.code != 0) {
                            throw Exception("Error adding contract spec: ${it.txResponse.rawLog}")
                        }

                        log.info("$logPrefix contract spec batch made it to mempool with txhash = ${it.txResponse.txhash}")

                        it.txResponse.txhash
                    }
                }
            }

            log.info("$logPrefix waiting for ${contractSpecHashes.size} contract spec batches to complete")

            // wait for all contract specs to be written
            if (!contractSpecHashes.waitForAllTxsToCompleteSuccessfully(OffsetDateTime.now().plusSeconds(
                    chaincodeProperties.contractSpecTxTimeoutS.toLong()
            ))) {
                throw Exception("Timeout waiting for all contract spec txs to complete successfully")
            }

            log.info("$logPrefix all ${contractSpecHashes.size} contract spec batches completed successfully")

            scopeSpecTx.chunked(chaincodeProperties.scopeSpecTxBatchSize).forEach { messages ->
                log.info("$logPrefix sending batch of ${messages.size} scope spec messages")
                val txBody = messages.toTxBody(provenanceGrpc.getLatestBlock().block.header.height + chaincodeProperties.blockHeightTimeoutInterval)

                synchronized(provenanceGrpc) {
                    batchTx(txBody, applyMultiplier = false).also {
                        if (it.txResponse.code != 0) {
                            throw Exception("Error adding scope spec: ${it.txResponse.rawLog}")
                        }

                        log.info("$logPrefix scope spec batch made it to mempool with txhash = ${it.txResponse.txhash}")
                    }
                }
            }
        } catch(e: Throwable) {
            log.warn("$logPrefix failed to add contract spec: ${e.message}")
            throw e
        }
    }

    fun List<String>.waitForAllTxsToCompleteSuccessfully(deadline: OffsetDateTime): Boolean {
        var remaining = this
        while (deadline.isAfter(OffsetDateTime.now())) {
            remaining = remaining.filterNot {
                val txResponse = try {
                    provenanceGrpc.getTx(it)
                } catch (e: StatusRuntimeException) {
                    log.info("[waitForAllTxsToCompleteSuccessfully] received error: ${e.message}")
                    return@filterNot false
                }

                if (txResponse.code > 0) {
                    throw Exception("Error adding contract spec while waiting for completion (code ${txResponse.code}): ${txResponse.rawLog}")
                }

                txResponse.height > 0 && txResponse.code == 0 // filtering out transactions that have completed successfully
            }

            if (remaining.isEmpty()) {
                return true
            }

            Thread.sleep(1000)
        }
        return false
    }

    fun batchTx(body: TxBody, applyMultiplier: Boolean = true): BroadcastTxResponse {
        val accountNumber = accountInfo.accountNumber
        val sequenceNumber = getAndIncrementSequenceNumber()

        val estimate = provenanceGrpc.estimateTx(body, accountNumber, sequenceNumber)

        if (applyMultiplier && gasMultiplierDailyCount < chaincodeProperties.maxGasMultiplierPerDay) {
            log.info("setting gasMultiplier to ${chaincodeProperties.gasMultiplier} (current count = $gasMultiplierDailyCount)")
            estimate.setGasMultiplier(chaincodeProperties.gasMultiplier)
            gasMultiplierDailyCount++
        } else if (!applyMultiplier) {
            log.info("skipping gasMultiplier due to override")
        } else {
            log.info("skipping gasMultiplier due to daily limit")
        }

        estimate.messageFeesNanoHash = body.messagesList.count { it.typeUrl == MEMORIALIZE_MESSAGE_TYPEURL } * chaincodeProperties.memorializeMsgFeeNanoHash
        log.info("Adding additional message fee of ${estimate.messageFeesNanoHash}nhash (${estimate.messageFeesNanoHash / 1000000000L}hash) for ${body.messagesList.size} messages")

        return provenanceGrpc.batchTx(body, accountNumber, sequenceNumber, estimate)
    }

    /**
     * Sequence Number Tracking
     */
    private var sequenceNumberAndOffset = accountInfo.sequence to 0L

    private fun getAndIncrementSequenceNumber(): Long {
        return sequenceNumberAndOffset.let { (sequence, offset) ->
            sequence + offset
        }.also {
            sequenceNumberAndOffset = sequenceNumberAndOffset.first to sequenceNumberAndOffset.second + 1
        }
    }

    private fun decrementSequenceNumber() {
        sequenceNumberAndOffset = sequenceNumberAndOffset.first to Math.max(sequenceNumberAndOffset.second - 1, 0)
        log.info("Decremented sequence number to ${sequenceNumberAndOffset.first + sequenceNumberAndOffset.second}")
    }

    private fun resetSequenceNumber() {
        log.info("Resetting sequence number")
        sequenceNumberAndOffset = provenanceGrpc.accountInfo().sequence to 0L
        log.info("Sequence number reset to ${sequenceNumberAndOffset.first}")
    }

    private fun waitForBlockCut() {
        val currentHeight = provenanceGrpc.getLatestBlock().block.header.height
        log.info("Waiting for block cut...")
        for (i in 1..100) {
            Thread.sleep(2500)
            val newHeight = provenanceGrpc.getLatestBlock().block.header.height
            if (newHeight > currentHeight) {
                log.info("block cut detected")
                break
            }
        }
        resetSequenceNumber()
    }
}
