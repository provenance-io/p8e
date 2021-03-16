package io.provenance.engine.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeName
import com.tinder.scarlet.WebSocket
import feign.RequestLine
import io.p8e.crypto.Hash
import io.provenance.engine.stream.domain.*
import io.provenance.p8e.shared.extension.logger
import org.springframework.stereotype.Component

data class BlockchainInfo(
    val lastHeight: Long,
    @JsonProperty("block_metas")
    val blockMetas: List<BlockMeta>,
)

data class BlockMeta(
    @JsonProperty("block_id")
    val blockId: BlockID,
    @JsonProperty("block_size")
    val blockSize: Int,
    val header: BlockHeader,
    @JsonProperty("num_txs")
    val numTxs: Int,
)

data class BlockID(
    val hash: String,
    val parts: PartSetHeader,
)

data class PartSetHeader(
    val total: Int,
    val hash: String,
)

class BlockchainInfoRequest(
    minHeight: Long,
    maxHeight: Long
) : RPCRequest("blockchain", BlockchainInfoParams(minHeight.toString(), maxHeight.toString()))

data class BlockchainInfoParams(
    val minHeight: String,
    val maxHeight: String
)

class BlockRequest(
    height: Long
) : RPCRequest("block", BlockParams(height.toString()))

data class BlockParams(val height: String)

data class BlockResponse(
    @JsonProperty("block_id")
    val blockId: BlockID,
    val block: Block
)

class BlockResultsRequest(
    height: Long
) : RPCRequest("block_results", BlockParams(height.toString()))

data class BlockResults(
    val height: Long,
    @JsonProperty("txs_results")
    val txsResults: List<TxResult>
)

data class TxResult(
    val code: Int?,
    val data: String?,
    val log: String,
    val info: String,
    val gasWanted: Long,
    val gasUsed: Long,
    val events: List<Event>
)

data class ABCIInfoResponse(
    val response: ABCIInfo
)

data class ABCIInfo(
    val data: String,
    @JsonProperty("last_block_height")
    val lastBlockHeight: Long,
    @JsonProperty("last_block_app_hash")
    val lastBlockAppHash: String
)

@Component
interface RPCClient {
    @RequestLine("GET /")
    fun blockchainInfo(request: BlockchainInfoRequest): RPCResponse<BlockchainInfo>
    @RequestLine("GET /")
    fun block(request: BlockRequest): RPCResponse<BlockResponse>
    @RequestLine("GET /")
    fun blockResults(request: BlockResultsRequest): RPCResponse<BlockResults>
    @RequestLine("GET /")
    fun abciInfo(request: RPCRequest = RPCRequest("abci_info")): RPCResponse<ABCIInfoResponse>
}

@Component
class TransactionQueryService(val rpcClient: RPCClient) {

    fun blocksWithTransactions(minHeight: Long, maxHeight: Long): List<Long> {
        val info = blockchainInfo(minHeight, maxHeight)

        return info.blockMetas
            .filter { it.numTxs > 0 }
            .map { it.header.height }
    }

    fun blockchainInfo(minHeight: Long, maxHeight: Long) = rpcClient.blockchainInfo(BlockchainInfoRequest(minHeight, maxHeight)).result

    fun block(height: Long) = rpcClient.block(BlockRequest(height)).result

    fun blockResults(height: Long) = rpcClient.blockResults(BlockResultsRequest(height)).result

    fun abciInfo() = rpcClient.abciInfo().result.response
}
