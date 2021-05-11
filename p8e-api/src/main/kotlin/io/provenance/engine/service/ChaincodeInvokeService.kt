package io.provenance.engine.service

import com.fasterxml.jackson.databind.ObjectMapper
import cosmos.auth.v1beta1.Auth
import cosmos.base.abci.v1beta1.Abci
import cosmos.tx.v1beta1.ServiceOuterClass.BroadcastTxResponse
import cosmos.tx.v1beta1.TxOuterClass.TxBody
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
import io.provenance.p8e.shared.domain.ContractSpecificationRecord
import io.provenance.p8e.shared.domain.ContractTxResult
import io.provenance.p8e.shared.domain.ScopeSpecificationRecord
import io.provenance.pbc.clients.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture
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
    private val sc: SimpleClient,
    private val chaincodeProperties: ChaincodeProperties,
    private val provenanceGrpc: ProvenanceGrpcService,
) : IChaincodeInvokeService {
    private val log = logger()

    private val objectMapper = ObjectMapper().configureProvenance()
    private val indexRegex = "^.*message index: (\\d+).*$".toRegex()

    // private val queue = ConcurrentHashMap<UUID, BlockchainTransaction>()

    // thread safe queue because this spans the worker thread and the enqueue
    private val queue = LinkedBlockingQueue<ContractRequestWrapper>(1_000)

    // non-thread safe data structures that are only used within the worker thread
    private val batch = HashSet<ContractRequestWrapper>()
    private val blockScopeIds = HashSet<String>()
    private val priorityScopeBacklog = HashMap<String, LinkedList<ContractRequestWrapper>>()
    private var currentBlockHeight = 0L

    // helper extensions for batch management
    private val HashSet<ContractRequestWrapper>.executionUuids: List<UUID> get() = map { it.executionUuid }
    private fun HashSet<ContractRequestWrapper>.removeContract(contract: ContractRequestWrapper) {
        remove(contract)
        attemptTracker.remove(contract)
    }

    private val attemptTracker = HashMap<ContractRequestWrapper, Int>()
    private var ContractRequestWrapper.attempts: Int
        get() = attemptTracker[this] ?: 0
        set(attempt: Int) { attemptTracker[this] = attempt }

    init {
        thread(isDaemon = true, name = "bc-tx-batch") {
            memorializeBatchTx()
        }
    }

    fun memorializeBatchTx() {
        log.info("Starting bc-tx-batch thread")

        while(true) {
            provenanceGrpc.getLatestBlock()
                .takeIf { it.block.header.height > currentBlockHeight }
                ?.let {
                    log.info("Clearing blockScopeIds")
                    currentBlockHeight = it.block.header.height
                    blockScopeIds.clear()
                }

            // attempt to load the batch with scopes that were previously passed on due to not
                // wanting to send the same scope in the same block
            while (batch.size < chaincodeProperties.txBatchSize) {
                // filter the backlog for scopes that aren't in the block and then pick a random
                    // scope and insert it into the block
                priorityScopeBacklog.filterKeys { !blockScopeIds.contains(it) }
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
                        blockScopeIds.add(message.request.scopeId)
                        batch.add(message)
                    } ?: break
            }

            while (batch.size < chaincodeProperties.txBatchSize) {
                queue.poll(chaincodeProperties.maxTxFlushDelay.toLong(), TimeUnit.MILLISECONDS)?.let { message ->
                    if (!blockScopeIds.contains(message.request.scopeId)) {
                        log.debug("adding ${message.request.scopeId} to batch")

                        blockScopeIds.add(message.request.scopeId)
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
                log.info("No batch available, waiting...")
                log.debug("Internal structures\nblockScopeIds: $blockScopeIds\npriorityFutureScopeToQueue: ${priorityScopeBacklog.entries.map { e -> "${e.key} => ${e.value.size}"}}")

                continue
            }

            log.info("Sending batch with size: ${batch.size} ${batch.map { it.request.scopeId }} total backlog queue size: ${priorityScopeBacklog.values.fold(0) { acc, list ->  acc + list.size }}")
            log.debug("Internal structures\nblockScopeIds: $blockScopeIds\npriorityFutureScopeToQueue: ${priorityScopeBacklog.entries.map { e -> "${e.key} => ${e.value.size}"}}")

            // Create a new batch of transactions.
            // val tx = batch {
            //     var index = 0;
            //     batch.map {
            //         batchIndex.put(index++, it.key)

            //         // Note that we've attempted this transaction before.
            //         // TODO add attempts
            //         it.value.attempts++

            //         prepare { it.value.txPreparer }
            //     }
            // }
            // TODO clear batch and populate next pre-batch

            try {
                val (txBody, batchIndex) = batch.mapIndexed { index, requestWrapper ->
                    requestWrapper.attempts++
                    requestWrapper.request to (index to requestWrapper)
                }.unzip().let { (msgs, batchIndex) ->
                    msgs.toTxBody() to batchIndex.toMap()
                }

                // Send the transactions to the blockchain.
                val resp = synchronized(provenanceGrpc) { batchTx(txBody) }

                if (resp.txResponse.code != 0) {
                    // adding extra raw logging during exceptional cases so that we can see what typical responses look like while this interface is new
                    log.info("Abci.TxResponse from chain ${resp.txResponse}")

                    try {
                        handleTransactionException(resp.txResponse, batchIndex)
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
                }
            } catch (t: Throwable) {
                // TODO I'm pretty sure since this is now grpc the only things that would be caught in here
                    // are network related problems and we should probably retry instead of fail but
                        // it doesn't harm much until we know more to push the retry onto the user
                try {
                    // TODO rework this
                    decrementSequenceNumber()
                    log.warn("Unexpected chain execution error", t)

                    val executionUuidsToFail = handleBatchRetry(t.message ?: "Unexpected chain execution error")

                    transaction { transactionStatusService.setEnvelopeErrors(t.toString(), executionUuidsToFail) }
                } catch (e: Exception) {
                    log.error("Handle generic transaction error - ${batch.executionUuids} need _manual_ intervention", e)
                }
            }

            batch.clear()
        }
    }

     private fun handleTransactionException(response: Abci.TxResponse, batchIndex: Map<Int, ContractRequestWrapper>) {
         decrementSequenceNumber()

         // default to failing all envelopes, unless a specific envelope can be identified or certain envelopes can be retried
         var executionUuidsToFail = batch.executionUuids

         val retryable = response.code == SIGNATURE_VERIFICATION_FAILED ||
             response.rawLog.contains("txn invalid: rmi submit") // can be false negative due to timeout... I don't think we are actually catching this as it is a PBTransactionException, not PBTransactionResultException
         // todo: what is the new way of specifying the rmi submit thing? Is that now equivalent to a grpc network error?

         val match = indexRegex.find(response.rawLog)?.let {
             if (it.groupValues.size == 2)
                 it.groupValues[1]
             else
                 null
         }
         log.info("Found index ${match}")

         val errorMessage = "${response.code} - ${response.rawLog}"

         if (match == null) {
             if (retryable) {
                 executionUuidsToFail = handleBatchRetry(errorMessage)
             } else {
                 batch.forEach {
                     it.future.completeExceptionally(IllegalStateException(errorMessage))
                 }
             }
         } else {
             // Ship the error back for the bad index.
             val erroredContract = batchIndex[match!!.toInt()]!!
             batch.removeContract(erroredContract)
             executionUuidsToFail = listOf(erroredContract.executionUuid)
             erroredContract.future.completeExceptionally(IllegalStateException(errorMessage))

             // Resend the rest of the contracts for execution.
             batch.forEach {
                 queue.put(it)
             }
         }

         transaction {
             TransactionStatusRecord.insert(response.txhash, batch.executionUuids).let {
                 transactionStatusService.setError(it, errorMessage, executionUuidsToFail)
             }
         }
     }

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

                it.executionUuid
            }

        // Because this could be a sequencing issue, let's wait a full block cut cycle before we retry.
        log.info("Waiting for block cut...")
        for (i in 1..100) {
            Thread.sleep(2500)
            val blockHasBeenCut = synchronized(sc) { provenanceGrpc.blockHasBeenCut() }
            if (blockHasBeenCut) {
                log.info("block cut detected")
                break
            }
        }

        if (batch.isNotEmpty()) {
            log.warn("Retrying due to ${errorMessage}")
            batch.forEach {
                queue.put(it)
            }
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
        historicalContractSpecs: Collection<ContractSpecificationRecord>,
        contractSpecs: List<ContractSpec>,
    ) {
        log.info("received a set of contract specs: ${contractSpecs.size} and scope specs: ${scopeSpecs.size}")

        val owners = listOf(accountProvider.bech32Address())
        val historicalContractSpecsById = historicalContractSpecs.groupBy { it.scopeSpecificationUuid }

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
                        .addAllContractSpecIds(historicalContractSpecsById.getValue(it.id.value).map { p -> p.provenanceHash.base64Decode().toByteString() })
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
            val txBody = contractSpecTx.plus(scopeSpecTx).toTxBody()

            synchronized(provenanceGrpc) {
                batchTx(txBody).also {
                    if (it.txResponse.code != 0) {
                        throw Exception("Error adding contract spec: ${it.txResponse.rawLog}")
                    }

                    log.info("batch made it to mempool with txhash = ${it.txResponse.txhash}")
                }
            }
        } catch(e: Throwable) {
            log.warn("failed to add contract spec: ${e.message}")
            throw e
        }
    }

    fun batchTx(body: TxBody): BroadcastTxResponse {
        val accountInfo = provenanceGrpc.accountInfo()
        log.warn("account info = $accountInfo")

        val estimate = provenanceGrpc.estimateTx(body, accountInfo)
        log.warn("estimate = $estimate")

        return provenanceGrpc.batchTx(body, accountInfo, accountInfo.getAndIncrementSequenceOffset(), estimate)
    }

    private fun ProvenanceGrpcService.blockHasBeenCut(): Boolean = provenanceGrpc.accountInfo().blockHasBeenCut()

    private var sequenceNumberAndOffset = 0L to 0L

    private fun Auth.BaseAccount.getAndIncrementSequenceOffset(): Long {
        if (blockHasBeenCut()) {
            sequenceNumberAndOffset = sequence to 0
        }

        return sequenceNumberAndOffset.second.also {
            sequenceNumberAndOffset = sequence to it + 1
        }
    }

    private fun Auth.BaseAccount.blockHasBeenCut(): Boolean = (sequence > sequenceNumberAndOffset.first) || sequenceNumberAndOffset.second == 0L

    private fun decrementSequenceNumber() {
        sequenceNumberAndOffset = sequenceNumberAndOffset.first to Math.max(sequenceNumberAndOffset.second - 1, 0)
    }
}
