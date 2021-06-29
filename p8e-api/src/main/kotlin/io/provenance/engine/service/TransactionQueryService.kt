package io.provenance.engine.service

import cosmos.base.abci.v1beta1.Abci
import io.grpc.StatusRuntimeException
import io.provenance.engine.domain.*
import org.springframework.stereotype.Service

open class TransactionQueryError(message: String): Throwable(message)
class TransactionNotFoundError(message: String): TransactionQueryError(message)

@Service
class TransactionQueryService(
    private val rpcClient: RPCClient,
    private val provenanceGrpcService: ProvenanceGrpcService,
) {
    @Throws(TransactionQueryError::class)
    fun fetchTransaction(hash: String): Abci.TxResponse {
        return try {
            provenanceGrpcService.getTx(hash)
        } catch (e: StatusRuntimeException) {
            if (e.status.description?.contains("not found") != false) {
                throw TransactionNotFoundError(e.status?.description ?: "Transaction $hash not found")
            }
            throw TransactionQueryError(e.message ?: "Transaction $hash query error")
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
