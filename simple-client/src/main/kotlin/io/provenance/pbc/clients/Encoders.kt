package io.provenance.pbc.clients

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

data class GasEstimate(val gas_estimate: String)

private val <A, B> List<Pair<A, B>>.keys get() = this.map { it.first }
private val <A, B> List<Pair<A, B>>.values get() = this.map { it.second }

data class GasEstimates(val gas_estimates: List<Pair<String, Long>>) {
    companion object {
        private const val feeAdjustment = 0.025
    }

    val total = gas_estimates.values.sum()
    val fees = (total * feeAdjustment).roundUp()
}

data class AminoAny<T>(val type: String, val value: T)
data class AminoQuery<T>(val height: String, val result: T)

fun List<StdTx>.merge(): StdTx {
    require(isNotEmpty()) { "cannot flatten an empty list" }
    return first().copy(msg = flatMap { it.msg })
}

data class PreparedStdTx(val type: String?, val value: JsonNode?, val gas_estimate: String?) {
    companion object {
        const val stdTxType = "cosmos-sdk/StdTx"
    }

    val isTx: Boolean = listOf(type, value).all { it != null } && type == stdTxType
    val isEstimate: Boolean = gas_estimate != null

    fun asTx(): StdTx {
        requireNotNull(type) { "'type' cannot be null" }
        requireNotNull(value) { "'value' cannot be null" }
        require(type == stdTxType) { "type:$type must be $stdTxType" }
        return value.asType(StdTx::class)
    }

    fun asEstimate(): GasEstimate {
        requireNotNull(gas_estimate) { "'gas_estimate' cannot be null" }
        return GasEstimate(gas_estimate)
    }
}

private val encoderLogger = LoggerFactory.getLogger("io.provenance.pbc.clients.Encoders")

fun prepareSignDoc(
        om: ObjectMapper,
        chainId: String,
        accountNum: String,
        sequence: String,
        fee: StdTxFee,
        msgs: List<AminoAny<Any>>,
        memo: String
): StdSignDoc {
    val r = om.reader()
    val w = om.writer()
    val rawFee = r.readTree(w.writeValueAsString(fee))
    val msgBytes = msgs.map { r.readTree(w.writeValueAsString(it)) }

    return StdSignDoc(
            chain_id = chainId,
            account_number = accountNum,
            sequence = sequence,
            memo = memo,
            fee = rawFee,
            msgs = msgBytes
    )
}

fun StdTx.signPayloads(
        om: ObjectMapper,
        baseReq: BaseReq,
        txFee: StdTxFee,
        signer: (ByteArray) -> List<StdSignature>
): StdTx {
    val signDoc = prepareSignDoc(
            om,
            baseReq.chain_id,
            baseReq.account_number,
            baseReq.sequence,
            txFee,
            msg,
            baseReq.memo
    )

    val bz = om.writer().writeValueAsBytes(signDoc)
    encoderLogger.trace("signPayloads(${baseReq.memo}) -> signingDoc:${String(bz)}")
    return copy(signatures = signer(bz))
}
