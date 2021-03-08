package io.provenance.pbc.clients

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import feign.Param
import feign.RequestLine
import java.util.*

interface Tendermint {
    @RequestLine("GET /block_results?height={offset}")
    fun getResult(@Param("offset") offset: Int = 0): JsonRPC<ABCIBlockResults>

    @RequestLine("GET /block?height={offset}")
    fun getBlock(@Param("offset") offset: Int = 0): JsonRPC<JsonNode>

    @RequestLine("GET /abci_info")
    fun abciInfo(): JsonRPC<Response<ABCIInfo>>

    @RequestLine("GET /num_unconfirmed_txs")
    fun unconfirmedTransactions(): JsonRPC<UnconfirmedTx>

    @RequestLine("GET /health")
    fun health(): JsonRPC<JsonNode>

    @RequestLine("GET /status")
    fun status(): JsonRPC<NodeStatus>

    @RequestLine("GET /tx?hash=0x{hash}")
    fun tx(@Param("hash") sha256Hex: String): JsonRPC<TxResult>

    /*
    Other Available endpoints:
    /dump_consensus_state
    /net_info
    /unconfirmed_txs
    /validators
    /blockchain?minHeight=_&maxHeight=_
     */

}

data class JsonRPC<T>(
        val jsonrpc: String,
        val id: Int,
        val result: T
)

data class Response<T>(
        val response: T
)

data class NodeStatus(
        val node_info: NodeInfo,
        val sync_info: SyncInfo,
        val validator_info: Validator
)

data class NodeInfo (
    val protocol_version: JsonNode,
    val id: String,
    val listen_addr: String,
    val network: String,
    val version: String,
    val channels: String,
    val moniker: String,
    val other: JsonNode
    )

data class SyncInfo(
        val latest_block_hash: String,
        val latest_app_hash: String,
        val latest_block_height: Int,
        val latest_block_time: Date,
        val earliest_block_hash: String,
        val earliest_app_hash: String,
        val earliest_block_height: Int,
        val earliest_block_time: Date,
        val catching_up: Boolean
)
// ABCI Block Results Format
data class ABCIBlockResults(
        val height: Int,
        val txs_results: List<ResponseDeliverTx>?,
        val begin_block_events: List<Event>?,
        val end_block_events: List<Event>?,
        val validator_updates: List<ValidatorUpdate>?,
        val consensus_param_updates: ConsensusParams?
)

data class Event(val type: String, val attributes: List<Attribute>?)

data class Attribute(
		@JsonDeserialize(using = Base64String::class)
        val key: String?,
		@JsonDeserialize(using = Base64String::class)
        val value: String?
)

data class ABCIInfo(val data: String, val last_block_height: Int, val last_block_app_hash: String)

data class UnconfirmedTx(
        @JsonAlias("n_txs")
        val numberOfTransactions: Int,
        val total: Int,
        val total_bytes: Int,
        val txs: JsonNode
)

data class Validator(val address: String?, val pub_key: StdPubKey, val voting_power: Int)

data class ValidatorUpdate(val pub_key: StdPubKey, val power: Int)

data class ConsensusParams(val block: JsonNode, val evidence: JsonNode, val validator: JsonNode, val version: VersionParam)

data class VersionParam(val app_version: Int)

data class TxResult(
        val hash: String,
        val height: Int,
        val index: Int,
        val tx_result:ResponseDeliverTx,
        val tx: String
)

data class ResponseDeliverTx(
        val code: Int,
        val data: JsonNode,
		@JsonIgnore
        val log: String?,
        val info: String,
		@JsonAlias("gasWanted") // allows this to work with tendermint < 33.6 and 0.34+
        val gas_wanted: Int,
		@JsonAlias("gasUsed") // allows this to work with tendermint < 33.6 and 0.34+
        val gas_used: Int,
        val events: List<Event>,
        val codespace: String
)

class Base64String: JsonDeserializer<String>() {
	override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): String {
		return String(Base64.getDecoder().decode(p?.valueAsString))
	}
}

