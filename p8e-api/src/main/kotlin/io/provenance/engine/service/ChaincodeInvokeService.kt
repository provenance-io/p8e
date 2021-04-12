package io.provenance.engine.service

import com.fasterxml.jackson.databind.ObjectMapper
import cosmos.auth.v1beta1.Auth
import cosmos.tx.v1beta1.ServiceOuterClass.BroadcastTxResponse
import cosmos.tx.v1beta1.TxOuterClass.TxBody
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractSpecs
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.Contracts
import io.p8e.util.base64Decode
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toUuidProv
import io.p8e.util.configureProvenance
import io.p8e.util.toByteString
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
import org.springframework.scheduling.annotation.Scheduled
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
    val uuid: UUID,
    val request: MsgP8eMemorializeContractRequest,
    val future: CompletableFuture<ContractTxResult>,
    val attempt: Int = 0,
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

            // We need to store the batch indexes to map to error messages.
            val batchIndexToScope = batch
                .mapIndexed { index, it -> index to it.request.scopeId }
                .toMap()

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

            // val transactionExecutionUuids = batch.map { it.value.executionUuid }
            try {
                // Send the transactions to the blockchain.
                val resp = synchronized(provenanceGrpc) {
                    batchTx(batch.map { it.request }.toTxBody()).also {
                        if (it.txResponse.code != 0) {
                            // adding extra raw logging during exceptional cases so that we can see what typical responses look like while this interface is new
                            log.info("Abci.TxResponse from chain ${it.txResponse}")

                            // try {
                            //     handleTransactionException(it.txResponse, batchIndexToScope)
                            // } catch (e: Exception) {
                                // log.error("Handle typed transaction error - ${batch.map { it.uuid }} need _manual_ intervention", e)
                            log.error("Handle typed transaction error - ${batch.map { it.uuid }} need _manual_ intervention")
                            // }
                        }

                        log.info("batch made it to mempool with txhash = ${it.txResponse.txhash}")
                    }
                }

                // We successfully received a transaction hash so we can assume these will complete shortly.
                transaction { TransactionStatusRecord.insert(resp.txResponse.txhash, batch.map { it.uuid }) }
                batch.map { it.future.complete(ContractTxResult(it.request.scopeId)) }
            } catch (t: Throwable) {
                // TODO I'm pretty sure since this is now grpc the only things that would be caught in here
                    // are network related problems and we should probably retry instead of fail but
                        // it doesn't harm much until we know more to push the retry onto the user
                try {
                    // TODO rework this
                    decrementSequenceNumber()
                    log.warn("Unexpected chain execution error", t)

                    transaction { transactionStatusService.setEnvelopeErrors(t.toString(), batch.map { it.uuid }) }
                    batch.map { it.future.completeExceptionally(t) }
                } catch (e: Exception) {
                    log.error("Handle generic transaction error - ${batch.map { it.uuid }} need _manual_ intervention", e)
                }
            }

            batch.clear()
        }
    }

    // private fun handleTransactionException(response: TxResponse, batchIndexToScope: Map<Int, String>) {
    //     decrementSequenceNumber()

    //     // default to failing all envelopes, unless a specific envelope can be identified or certain envelopes can be retried
    //     var executionUuidsToFail = batch.map { it.uuid }

    //     val retryable = response.code == SIGNATURE_VERIFICATION_FAILED ||
    //         response.rawLog.contains("txn invalid: rmi submit") // can be false negative due to timeout... I don't think we are actually catching this as it is a PBTransactionException, not PBTransactionResultException

    //     val match = indexRegex.find(response.rawLog)?.let {
    //         if (it.groupValues.size == 2)
    //             it.groupValues[1]
    //         else
    //             null
    //     }
    //     log.info("Found index ${match}")

    //     val errorMessage = "${response.code} - ${response.rawLog}"

    //     if (match == null) {
    //         var printedException = false
    //         if (retryable) {
    //             executionUuidsToFail = batch.filter { it.attempt > 4 }
    //                 .map {
    //                     if (!printedException) {
    //                         log.warn("Exception couldn't be resolved", e)
    //                         printedException = true
    //                     }

    //                     log.warn("Exceeded max retry attempts for execution: ${it.uuid}")
    //                     batch.remove(it.key)!!.future.completeExceptionally(IllegalStateException(errorMessage))

    //                     it.value.executionUuid
    //                 }

    //             // Because this could be a sequencing issue, let's wait a full block cut cycle before we retry.
    //             log.info("Waiting for block cut...")
    //             for (i in 1..100) {
    //                 Thread.sleep(2500)
    //                 val blockHasBeenCut = synchronized(sc) { sc.blockHasBeenCut() }
    //                 if (blockHasBeenCut) {
    //                     log.info("block cut detected")
    //                     break
    //                 }
    //             }

    //             log.warn("Retrying due to ${e.reply.raw_log}")
    //             batch.forEach {
    //                 queue.put(it.key, it.value)
    //             }
    //         } else {
    //             batch.forEach {
    //                 it.value.future.completeExceptionally(IllegalStateException(errorMessage))
    //             }
    //         }
    //     } else {
    //         // Ship the error back for the bad index.
    //         val erroredUuid = batchIndex[match!!.toInt()]
    //         batch.remove(erroredUuid)!!.also {
    //             executionUuidsToFail = listOf(it.executionUuid)
    //         }.future.completeExceptionally(IllegalStateException(errorMessage))

    //         // Resend the rest of the contracts for execution.
    //         batch.forEach {
    //             queue.put(it.key, it.value)
    //         }
    //     }

    //     transaction {
    //         TransactionStatusRecord.insert(e.reply.txhash, transactionExecutionUuids).let {
    //             transactionStatusService.setError(it, errorMessage, executionUuidsToFail)
    //         }
    //     }
    // }

    // // private fun parseTxReply(reply: SubmitStdTxReply): Map<UUID, ContractTxResult> {
    // //     val results = mutableMapOf<UUID, ContractTxResult>()

    // //     if(reply.logs.isNullOrEmpty()) {
    // //         log.info("batch made it to mempool with txhash = ${reply.txhash}")
    // //     }

    // //     reply.logs?.forEach { txReply ->
    // //         val result = ContractTxResult();
    // //         result.errorMsg = txReply.log

    // //         txReply.events.forEach { txEvent ->
    // //             log.info("Found message type ${txEvent.type}")
    // //             txEvent.attributes.forEach {
    // //                 log.info("\tk: ${it.key} v: ${it.value}")
    // //             }

    // //             val attrs = txEvent.attributes
    // //             when (txEvent.type) {
    // //                 "scope_updated", "scope_created" -> {
    // //                     result.scopeId = attrs.firstOrNull { it.key == "scope_id" }?.value
    // //                     result.scope = attrs.firstOrNull { it.key == "scope" }?.value
    // //                 } else -> {

    // //                 }
    // //             }
    // //         }

    // //         if (result.scopeId != null)
    // //             results.put(UUID.fromString(result.scopeId), result)
    // //     }

    // //     return results
    // // }

    // TODO refactor these functions

    fun offer(env: Envelope): Future<ContractTxResult> {
        val msg = when (env.contract.type!!) {
            Contracts.ContractType.FACT_BASED -> env.toProv(accountProvider.accountPrefix())
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

        return provenanceGrpc.batchTx(body, accountInfo, 0, estimate)
    }

    private fun ProvenanceGrpcService.blockHasBeenCut(): Boolean = provenanceGrpc.accountInfo().blockHasBeenCut()

    private var sequenceNumberAndOffset = 0L to 0 // todo: change left back to uint once https://github.com/FasterXML/jackson-module-kotlin/issues/396 fixed

    private fun Auth.BaseAccount.getAndIncrementSequenceOffset(): Int {
        if (blockHasBeenCut()) {
            sequenceNumberAndOffset = sequence to 0
        }

        return sequenceNumberAndOffset.second.also {
            sequenceNumberAndOffset = sequence to it + 1
        }
    }

    private fun Auth.BaseAccount.blockHasBeenCut(): Boolean = (sequence > sequenceNumberAndOffset.first) || sequenceNumberAndOffset.second == 0

    private fun decrementSequenceNumber() {
        sequenceNumberAndOffset = sequenceNumberAndOffset.first to Math.max(sequenceNumberAndOffset.second - 1, 0)
    }
}
