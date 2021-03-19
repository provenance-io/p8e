package io.provenance.engine.service

import io.provenance.engine.domain.*
import org.springframework.stereotype.Component

open class TransactionQueryError(message: String): Throwable(message)
class TransactionNotFoundError(message: String): TransactionQueryError(message)

@Component
class TransactionQueryService(val rpcClient: RPCClient) {
    @Throws(TransactionQueryError::class)
    fun fetchTransaction(hash: String): GetTxResult {
        return rpcClient.getTransaction(hash).let {
            if (it.error != null) {
                val message = "${it.error.message} - ${it.error.data}"
                if (it.result?.txResult == null) {
                    throw TransactionNotFoundError(message)
                }
                throw TransactionQueryError(message)
            }

            it.result!!
        }
    }

    fun blocksWithTransactions(minHeight: Long, maxHeight: Long): List<Long> {
        val info = blockchainInfo(minHeight, maxHeight)

        return info.blockMetas
            .filter { it.numTxs > 0 }
            .map { it.header.height }
    }

    fun blockchainInfo(minHeight: Long, maxHeight: Long) = rpcClient.blockchainInfo(BlockchainInfoRequest(minHeight, maxHeight)).result!!

    fun block(height: Long) = rpcClient.block(BlockRequest(height)).result!!

    fun blockResults(height: Long) = rpcClient.blockResults(BlockResultsRequest(height)).result!!

    fun abciInfo() = rpcClient.abciInfo().result!!.response
}
