package io.provenance.pbc.clients

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.provenance.pbc.clients.Denom.vspn
import io.provenance.pbc.clients.StdTxMode.block
import io.provenance.pbc.clients.StdTxMode.sync
import io.provenance.pbc.clients.jackson.SimpleClientProtoModule
import io.provenance.pbc.clients.tx.BatchTx
import io.provenance.pbc.clients.tx.BoundTx
import io.provenance.pbc.clients.tx.SingleTx
import io.provenance.pbc.clients.tx.Tx
import io.provenance.pbc.clients.tx.toBatch
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.reflect.KClass

val CANONICAL_OBJECT_MAPPER = ObjectMapper()
        .registerModule(SimpleClientProtoModule())
        .registerKotlinModule()
        .configureCanonical()

internal val omRead = CANONICAL_OBJECT_MAPPER.reader()

internal fun <T : Any> JsonNode.asType(clazz: KClass<T>) = let { omRead.treeToValue(this, clazz.java) }

internal val Any.log get() = LoggerFactory.getLogger(this::class.java)

internal fun requireEnv(name: String, prefix: String = "PBC_") =
        System.getenv("$prefix$name") ?: throw RuntimeException("env var '$prefix$name' not found")

object SimpleClientConfigs {
    const val CHAIN_ID = "CHAIN_ID"
    const val BECH32_ADDRESS = "BECH32_ADDRESS"
    const val SIGNER_CLASS = "SIGNER_CLASS"
    const val URI = "URI"
    const val TX_FEES = "TX_FEES"
    const val GAS = "GAS"
    const val GAS_ADJUSTMENT = "GAS_ADJUSTMENT"
}

typealias PayloadSigner = (ByteArray) -> List<StdSignature>

data class SimpleClientOpts(
        val chainId: String,
        val bech32Address: String,
        val signer: PayloadSigner,
        val uri: String = DEFAULT_URI,
        val txFees: List<Coin> = DEFAULT_TX_FEES,
        val gas: String = DEFAULT_MAX_GAS,
        val gasAdjustment: String = DEFAULT_GAS_ADJUSTMENT,
        val objectMapper: ObjectMapper = CANONICAL_OBJECT_MAPPER,
        val clientCfgs: List<BlockchainClientCfg> = emptyList()
) {
    companion object {
        const val DEV_CHAIN_ID = "pio-dev-chain"
        const val DEFAULT_URI = "http://localhost:1317"
        const val DEFAULT_MAX_GAS = "auto"
        const val DEFAULT_GAS_ADJUSTMENT = "1.2"
        val DEFAULT_TX_FEES = listOf(5000 coins vspn)


        fun fromEnv() = SimpleClientOpts(
                requireEnv(SimpleClientConfigs.CHAIN_ID),
                requireEnv(SimpleClientConfigs.BECH32_ADDRESS),
                Class.forName(requireEnv(SimpleClientConfigs.SIGNER_CLASS)).constructors.first().newInstance() as PayloadSigner,
                requireEnv(SimpleClientConfigs.URI),
                requireEnv(SimpleClientConfigs.TX_FEES).split(",").map { Coin.parse(it) },
                requireEnv(SimpleClientConfigs.GAS),
                requireEnv(SimpleClientConfigs.GAS_ADJUSTMENT)
        )
    }
}

/**
 * BlockReader creates an ABCI direct block interface into a node.
 * @param uri should be the ABCI interface on port 26657
 * @param apiKey is typically unused.  Some connections through Kong will require it
 */
fun BlockReader(uri: String="http://localhost:26657", apiKey: String="") = Blockchain(uri, apiKey, CANONICAL_OBJECT_MAPPER).new<Tendermint>()

open class TxContext(bc: Blockchain) {
    val accounts = bc.new<Accounts>()
    val names = bc.new<Names>()
    val banks = bc.new<Banks>()
    val contracts = bc.new<Contracts>()
    val contractSpecs = bc.new<ContractSpecs>()
    val txs = bc.new<Txs>()
    val markers = bc.new<Markers>()
    val migrate = bc.new<Migrate>()
}

open class SimpleClient(
        apiKey: String,
        private val opts: SimpleClientOpts,
        private val bc: Blockchain = Blockchain(opts.uri, apiKey, opts.objectMapper, *opts.clientCfgs.toTypedArray())
) : TxContext(bc) {
    private val log = LoggerFactory.getLogger(javaClass)
    private fun memo() = "request_id:${UUID.randomUUID()}"

    fun baseRequest(
            fromBech32: String,
            memo: String,
            fees: List<Coin>,
            gas: String,
            gasAdjustment: String,
            sequenceNumberOffset: Int = 0,
            fromAccountInfo: AccountInfo? = null,
            simulated: Boolean = false
    ): BaseReq {
        val from = fromAccountInfo ?: fetchAccountDetails(fromBech32).result.value

        return BaseReq(
                from.address,
                memo,
                opts.chainId,
                from.account_number.toString(),
                (from.sequence.toInt() + sequenceNumberOffset).toString(),
                fees,
                gas.ifBlank { "0" },
                gasAdjustment,
                simulated
        )
    }

    private fun Any.toJson() = opts.objectMapper.writer().writeValueAsString(this)
    private inline fun <reified T> JsonNode.toType(): T {
        return opts.objectMapper.readValue(opts.objectMapper.writeValueAsBytes(this), T::class.java)
    }

    fun estimateTx(
            from: String = opts.bech32Address,
            fees: List<Coin> = emptyList(),
            gas: String = "auto",
            gasAdjustment: String = opts.gasAdjustment,
            fromAccountInfo: AccountInfo? = null,
            batch: BatchTx
    ): GasEstimates {
        return estimateGas(baseRequest(from, "", fees, gas, gasAdjustment, fromAccountInfo = fromAccountInfo), batch)
    }

    fun runTx(
            from: String = opts.bech32Address,
            memo: String = memo(),
            fees: List<Coin> = opts.txFees,
            gas: String = opts.gas,
            gasAdjustment: String = opts.gasAdjustment,
            mode: StdTxMode = block,
            signer: PayloadSigner = opts.signer,
            sequenceNumberOffset: Int = 0,
            fromAccountInfo: AccountInfo? = null,
            singleTx: SingleTx
    ): SubmitStdTxReply {
        return batchTx(from, memo, fees, gas, gasAdjustment, mode, signer, sequenceNumberOffset, fromAccountInfo, singleTx.toBatch())
    }

    fun batchTx(
            from: String = opts.bech32Address,
            memo: String = memo(),
            fees: List<Coin> = opts.txFees,
            gas: String = opts.gas,
            gasAdjustment: String = opts.gasAdjustment,
            mode: StdTxMode = block,
            signer: PayloadSigner = opts.signer,
            sequenceNumberOffset: Int = 0,
            fromAccountInfo: AccountInfo? = null,
            batch: BatchTx
    ): SubmitStdTxReply {
        return sendTx(mode, baseRequest(from, memo, fees, gas, gasAdjustment, sequenceNumberOffset, fromAccountInfo), signer, batch)
    }

    private fun sendTx(
            mode: StdTxMode = block,
            base: BaseReq,
            signer: PayloadSigner,
            batch: BatchTx
    ): SubmitStdTxReply {
        // Prepare and merge the transactions into a single txn.
        val prepared = prepareBatchTx(base, batch).txns.map { it.second }.merge()

        // Sign the transaction.
        val signed = signTx(signer, base, prepared)

        // Send the transaction.
        val result = try {
            txs.tx(SubmitStdTx(signed, mode))
        } catch (e: CosmosRemoteInvocationException) {
            throw PBTransactionException("rmi submit ${base.memo}", e)
        }

        // Failed transaction?
        if (result.isFailed) {
            throw PBTransactionResultException("remote error: code:${result.code}", result)
        }

        return result
    }

    private fun estimateGas(base: BaseReq, batch: BatchTx): GasEstimates {
        // Prepare the gas estimates. Simulate must be true for these to process correctly.
        val estimated = prepareBatchTx(base.copy(simulate = true), batch).estimates

        // Reduce to sum() to get total gas estimate
        return estimated.fold(GasEstimates(emptyList())) { acc, est ->
            acc.copy(gas_estimates = acc.gas_estimates + est)
        }
    }

    // Generate the transactional batch.
    private fun prepareBatchTx(base: BaseReq, batch: BatchTx): BoundTx = Tx(this).also { it.batch() }.bind(base)

    private fun signTx(signer: PayloadSigner, base: BaseReq, req: StdTx): StdTx {
        log.debug("signTx(req:${req.toJson()})")

        return req.signPayloads(opts.objectMapper, base, StdTxFee(base.fees, base.gas), signer)
                .also { signed -> log.trace("signedReq: ${signed.toJson()}") }
    }

    fun fetchAccountDetails(bech32Addr: String) = accounts.fetch(bech32Addr)

    fun fetchTx(txHash: String) = txs.getTx(txHash)
}

class PBTransactionException(message: String, throwable: Throwable? = null)
    : RuntimeException("txn invalid: $message", throwable)

class PBTransactionResultException(message: String, val reply: SubmitStdTxReply)
    : RuntimeException("txn failed: $message ${reply}")
