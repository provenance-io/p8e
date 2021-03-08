package io.provenance.pbc.clients

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import feign.Param
import feign.RequestLine
import io.provenance.pbc.clients.jackson.PubKeyDeserializer

class CoinParseException(m: String) : RuntimeException(m)

data class Coin(val denom: String, val amount: String) {
    companion object {
        fun parse(value: String): Coin {
            if (value.isBlank()) {
                throw CoinParseException("cannot parse empty string into coin")
            }

            val splitAt = value.indexOfFirst { it.isLetter() }
            val amount = value.substring(0 until splitAt)
            val denom = value.substring(splitAt until value.length)

            if (amount.isBlank()) {
                throw CoinParseException("invalid format: missing amount for coin:$value")
            }

            if (!amount.all { it.isDigit() }) {
                throw CoinParseException("invalid format: amount not numeric for coin:$value")
            }

            if (denom.isBlank()) {
                throw CoinParseException("invalid format: missing denom for coin:$value")
            }

            return Coin(denom, amount)
        }
    }
}

data class Dec(val int: String)
data class DecCoin(val denom: String, val amount: Dec)

data class BaseReq(
        val from: String,
        val memo: String,
        val chain_id: String,
        val account_number: String,
        val sequence: String,
        val fees: List<Coin>,
        val gas: String,
        val gas_adjustment: String,
        val simulate: Boolean
)

enum class StdTxMode { block, sync, async }

abstract class Msg

data class StdTxFee(val amount: List<Coin>, val gas: String)

data class StdSignDoc(
        val chain_id: String,
        val account_number: String,
        val sequence: String,
        val fee: JsonNode,
        val msgs: List<JsonNode>,
        val memo: String
)

data class StdTx(
        val msg: List<AminoAny<Any>>,
        val fee: StdTxFee,
        val signatures: List<StdSignature>?,
        val memo: String
)

data class StdSignature(
        val pub_key: StdPubKey,
        val signature: ByteArray
)

@JsonDeserialize(using = PubKeyDeserializer::class)
data class StdPubKey(
        val type: String,
        @JsonAlias("data")
        val value: ByteArray?= ByteArray(0)
)


data class SubmitStdTx(val tx: StdTx, val mode: StdTxMode)
data class StdTxEventAttribute(val key: String, val value: String?)
data class StdTxEvent(val type: String, val attributes: List<StdTxEventAttribute>)
data class SubmitStdTxReplyLog(val msg_index: Int, val log: String, val events: List<StdTxEvent>)

data class SubmitStdTxReply(
        val height: String,
        val txhash: String,
        val codespace: String?,
        val code: Int,
        val raw_log: String,
        val logs: List<SubmitStdTxReplyLog>?,
        val gas_wanted: String?,
        val gas_used: String?
)

data class SimStdTxReply(
        val gas_estimate: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TxQuery(
        val height: String,
        val txhash: String,
        val code: Int?,
        val codespace: String?,
        val tx: JsonNode,
        val raw_log: String,
        val logs: List<SubmitStdTxReplyLog>?,
        val gas_wanted: String?,
        val gas_used: String?,
        val timestamp: String
)

// As extensions so jackson doesnt decode them
val SubmitStdTxReply.isFailed: Boolean get() = code != 0
val SubmitStdTxReply.isSuccessful: Boolean get() = !isFailed

interface Txs {
    @RequestLine("POST /txs")
    fun tx(submitStdTx: SubmitStdTx): SubmitStdTxReply

    @RequestLine("GET /txs/{txHash}")
    fun getTx(@Param("txHash") txHash: String): TxQuery
}
