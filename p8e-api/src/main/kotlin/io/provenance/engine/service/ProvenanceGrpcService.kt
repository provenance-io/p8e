package io.provenance.engine.service

import com.google.protobuf.Any
import com.google.protobuf.Message
import cosmos.auth.v1beta1.Auth
import cosmos.auth.v1beta1.QueryGrpc
import cosmos.auth.v1beta1.QueryOuterClass
import cosmos.base.abci.v1beta1.Abci.TxResponse
import cosmos.base.tendermint.v1beta1.Query.GetBlockByHeightRequest
import cosmos.base.tendermint.v1beta1.Query.GetLatestBlockRequest
import cosmos.base.tendermint.v1beta1.Query.GetLatestBlockResponse
import cosmos.base.tendermint.v1beta1.Query.GetNodeInfoRequest
import cosmos.base.tendermint.v1beta1.Query.GetNodeInfoResponse
import cosmos.base.tendermint.v1beta1.ServiceGrpc as NodeGrpc
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.crypto.secp256k1.Keys
import cosmos.tx.signing.v1beta1.Signing
import cosmos.tx.v1beta1.ServiceGrpc as TxGrpc
import cosmos.tx.v1beta1.ServiceOuterClass.*
import cosmos.tx.v1beta1.TxOuterClass.*
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.p8e.util.toByteString
import io.provenance.engine.config.ChaincodeProperties
import io.provenance.engine.crypto.Account
import io.provenance.engine.crypto.PbSigner
import io.provenance.metadata.v1.p8e.ContractSpec
import io.provenance.pbc.clients.roundUp
import org.kethereum.crypto.getCompressedPublicKey
import org.springframework.stereotype.Service
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import io.p8e.proto.ContractSpecs.ContractSpec as P8EContractSpec

@Service
class ProvenanceGrpcService(
    private val accountProvider: Account,
    private val chaincodeProperties: ChaincodeProperties
) {
    private val channel =  URI(chaincodeProperties.grpcUrl).let { uri ->
            Logger.getLogger("io.netty").setLevel(Level.ALL)
                io.provenance.p8e.shared.extension.logger().error("api = ${uri.host} ${uri.port}")
            NettyChannelBuilder.forAddress(uri.host, uri.port)
                .also {
                    if (uri.scheme == "grpcs") {
                        it.useTransportSecurity()
                    } else {
                        it.usePlaintext()
                    }
                }
                // TODO try default size of 3MB for now
                .maxInboundMessageSize(40 * 1024 * 1024) // ~ 20 MB
                .idleTimeout(5, TimeUnit.MINUTES)
                .keepAliveTime(60, TimeUnit.SECONDS) // ~ 12 pbc block cuts
                .keepAliveTimeout(20, TimeUnit.SECONDS)
                .build()
        }

    private val txService = TxGrpc.newBlockingStub(channel)
    private val accountService = QueryGrpc.newBlockingStub(channel)
    private val nodeService = NodeGrpc.newBlockingStub(channel)

    private val bech32Address = accountProvider.bech32Address()
    private val keyPair = accountProvider.getKeyPair()
    private val signer = PbSigner.signerFor(keyPair)

    fun accountInfo(): Auth.BaseAccount = accountService.account(QueryOuterClass.QueryAccountRequest.newBuilder()
            .setAddress(bech32Address)
            .build()
        ).run { account.unpack(Auth.BaseAccount::class.java) }

    fun nodeInfo(): GetNodeInfoResponse = nodeService.getNodeInfo(GetNodeInfoRequest.getDefaultInstance())

    // fun getLatestBlock(): GetLatestBlockResponse = nodeService.getLatestBlock(GetLatestBlockRequest.getDefaultInstance())

    // fun getLatestBlockHeight() = nodeService.getBlockByHeight(GetBlockByHeightRequest.newBuilder()
    //     .setHeight(getLatestBlock().)
    //     .build())

    fun getTx(hash: String): TxResponse = txService.getTx(GetTxRequest.newBuilder().setHash(hash).build()).txResponse

    fun signTx(body: TxBody, accountInfo: Auth.BaseAccount, sequenceNumberOffset: Int = 0, gasEstimate: GasEstimate = GasEstimate(0)): Tx {
        val authInfo = AuthInfo.newBuilder()
            .setFee(Fee.newBuilder()
                .addAllAmount(listOf(
                    CoinOuterClass.Coin.newBuilder()
                        .setDenom("nhash")
                        .setAmount(gasEstimate.fees.toString())
                        .build()
                )).setGasLimit((gasEstimate.total * 1.4).toLong())
            )
            .addAllSignerInfos(listOf(
                SignerInfo.newBuilder()
                    .setPublicKey(
                        Keys.PubKey.newBuilder()
                            .setKey(keyPair.getCompressedPublicKey().toByteString())
                            .build().toAny()
                    )
                    .setModeInfo(
                        ModeInfo.newBuilder().setSingle(
                            ModeInfo.Single.newBuilder()
                                .setModeValue(Signing.SignMode.SIGN_MODE_DIRECT_VALUE)
                        ))
                    .setSequence(accountInfo.sequence + sequenceNumberOffset)
                    .build()
            )).build()

        val signatures = SignDoc.newBuilder()
            .setBodyBytes(body.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .setChainId(chaincodeProperties.chainId)
            .setAccountNumber(accountInfo.accountNumber)
            .build()
            .toByteArray()
            .let { signer(it) }
            .map { it.signature.toByteString() }

        return Tx.newBuilder()
            .setBody(body)
            .setAuthInfo(authInfo)
            .addAllSignatures(signatures)
            .build()
    }

    fun estimateTx(body: TxBody, accountInfo: Auth.BaseAccount): GasEstimate =
        signTx(body, accountInfo).let {
            txService.simulate(SimulateRequest.newBuilder()
                .setTx(it)
                .build()
            )
        }.let { GasEstimate(it.gasInfo.gasUsed) }

    fun batchTx(body: TxBody, accountInfo: Auth.BaseAccount, sequenceNumberOffset: Int, estimate: GasEstimate): BroadcastTxResponse =
        signTx(body, accountInfo, sequenceNumberOffset, estimate).run {
            TxRaw.newBuilder()
                .setBodyBytes(body.toByteString())
                .setAuthInfoBytes(authInfo.toByteString())
                .addAllSignatures(signaturesList)
                .build()
        }.let {
            txService.broadcastTx(BroadcastTxRequest.newBuilder()
                .setTxBytes(it.toByteString())
                .setMode(BroadcastMode.BROADCAST_MODE_SYNC)
                .build()
            )
        }
}

fun Collection<Message>.toTxBody(): TxBody = TxBody.newBuilder()
    .addAllMessages(map { it.toAny() })
    .build()

fun Message.toTxBody(): TxBody = listOf(this).toTxBody()

fun Message.toAny(typeUrlPrefix: String = "") = Any.pack(this, typeUrlPrefix)

data class GasEstimate(val total: Long) {
    companion object {
        private const val feeAdjustment = 0.025
    }

    val fees = (total * feeAdjustment).roundUp()
}
