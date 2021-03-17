package io.provenance.engine.service

import io.provenance.engine.domain.*
import io.provenance.p8e.shared.extension.logger
import org.springframework.stereotype.Component

@Component
class TransactionQueryService(val rpcClient: RPCClient) {
    fun fetchTransaction(hash: String): GetTxResult {
        return rpcClient.getTransaction(hash).result
    }

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
