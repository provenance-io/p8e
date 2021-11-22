package io.provenance.engine.service

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.Message
import cosmos.auth.v1beta1.Auth
import cosmos.auth.v1beta1.QueryGrpc
import cosmos.auth.v1beta1.QueryOuterClass
import cosmos.base.abci.v1beta1.Abci.TxResponse
import cosmos.base.query.v1beta1.Pagination
import cosmos.base.tendermint.v1beta1.Query.*
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.crypto.secp256k1.Keys
import cosmos.tx.signing.v1beta1.Signing
import cosmos.tx.v1beta1.ServiceOuterClass.*
import cosmos.tx.v1beta1.TxOuterClass.*
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.p8e.engine.threadedMap
import io.p8e.proto.ContractScope
import io.p8e.util.ThreadPoolFactory
import io.p8e.util.toByteString
import io.p8e.util.toUuidProv
import io.provenance.engine.config.ChaincodeProperties
import io.provenance.engine.crypto.Account
import io.provenance.engine.crypto.PbSigner
import io.provenance.engine.util.toP8e
import io.provenance.metadata.v1.ContractSpecificationRequest
import io.provenance.metadata.v1.ScopeRequest
import io.provenance.metadata.v1.ScopeSpecification
import io.provenance.metadata.v1.ScopeSpecificationRequest
import io.provenance.metadata.v1.ScopeSpecificationWrapper
import io.provenance.metadata.v1.ScopeSpecificationsAllRequest
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.pbc.clients.roundUp
import org.kethereum.crypto.getCompressedPublicKey
import org.springframework.stereotype.Service
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import cosmos.base.tendermint.v1beta1.ServiceGrpc as NodeGrpc
import cosmos.tx.v1beta1.ServiceGrpc as TxGrpc
import io.provenance.metadata.v1.QueryGrpc as MetadataQueryGrpc

@Service
class ProvenanceGrpcService(
    private val accountProvider: Account,
    private val chaincodeProperties: ChaincodeProperties,
    private val affiliateService: AffiliateService,
) {
    companion object {
        val executor = ThreadPoolFactory.newFixedThreadPool(5, "prov-grpc-%d")
    }

    private val channel =  URI(chaincodeProperties.grpcUrl).let { uri ->
            Logger.getLogger("io.netty").setLevel(Level.ALL)
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
    private val metadataQueryService = MetadataQueryGrpc.newBlockingStub(channel)

    private val bech32Address = accountProvider.bech32Address()
    private val keyPair = accountProvider.getKeyPair()
    private val signer = PbSigner.signerFor(keyPair)

    fun accountInfo(): Auth.BaseAccount = accountService.account(QueryOuterClass.QueryAccountRequest.newBuilder()
            .setAddress(bech32Address)
            .build()
        ).run { account.unpack(Auth.BaseAccount::class.java) }

    fun nodeInfo(): GetNodeInfoResponse = nodeService.getNodeInfo(GetNodeInfoRequest.getDefaultInstance())

    fun getLatestBlock(): GetLatestBlockResponse = nodeService.getLatestBlock(GetLatestBlockRequest.getDefaultInstance())

    fun getTx(hash: String): TxResponse = txService.getTx(GetTxRequest.newBuilder().setHash(hash).build()).txResponse

    fun signTx(body: TxBody, accountNumber: Long, sequenceNumber: Long, gasEstimate: GasEstimate = GasEstimate(0)): Tx {
        val authInfo = AuthInfo.newBuilder()
            .setFee(Fee.newBuilder()
                .addAllAmount(listOf(
                    CoinOuterClass.Coin.newBuilder()
                        .setDenom("nhash")
                        .setAmount((gasEstimate.fees).toString())
                        .build()
                )).setGasLimit((gasEstimate.limit).toLong())
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
                    .setSequence(sequenceNumber)
                    .build()
            )).build()

        val signatures = SignDoc.newBuilder()
            .setBodyBytes(body.toByteString())
            .setAuthInfoBytes(authInfo.toByteString())
            .setChainId(chaincodeProperties.chainId)
            .setAccountNumber(accountNumber)
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

    fun estimateTx(body: TxBody, accountNumber: Long, sequenceNumber: Long): GasEstimate =
        signTx(body, accountNumber, sequenceNumber).let {
            txService.simulate(SimulateRequest.newBuilder()
                .setTx(it)
                .build()
            )
        }.let { GasEstimate(it.gasInfo.gasUsed) }

    fun batchTx(body: TxBody, accountNumber: Long, sequenceNumber: Long, estimate: GasEstimate): BroadcastTxResponse =
        signTx(body, accountNumber, sequenceNumber, estimate).run {
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

    fun retrieveScope(uuid: UUID): ContractScope.Scope = retrieveScope(uuid.toString())

    fun retrieveScope(address: String): ContractScope.Scope {
        val (scopeResponse, contractSpecHashLookup, scopeSpecificationName) = try {
            val scopeResponse = metadataQueryService.scope(
                ScopeRequest.newBuilder()
                    .setScopeId(address)
                    .setIncludeSessions(true)
                    .setIncludeRecords(true)
                    .build()
            )

            val contractSpecHashLookup = scopeResponse.sessionsList
                .map { it.contractSpecIdInfo.contractSpecAddr }
                .toSet()
                .threadedMap(executor) {
                    it to metadataQueryService.contractSpecification(
                        ContractSpecificationRequest.newBuilder()
                            .setSpecificationId(it)
                            .build()
                    ).contractSpecification.specification.hash
                }.toMap()

            val scopeSpecificationName = getScopeSpecification(scopeResponse.scope.scopeSpecIdInfo.scopeSpecUuid.toUuidProv()).description.name

            Triple(scopeResponse, contractSpecHashLookup, scopeSpecificationName)
        } catch (e: Exception) {
            logger().warn("Error retrieving scope details for address $address", e)
            throw e
        }

        return try {
            scopeResponse.toP8e(contractSpecHashLookup, scopeSpecificationName, affiliateService)
        } catch (e: Exception) {
            logger().warn("Failed to convert scope [address = $address, scopeUuid = ${scopeResponse.scope.scopeIdInfo.scopeUuid}, scopeSpecificationName = $scopeSpecificationName, scopeResponse = $scopeResponse, contractSpecHashLookup = $contractSpecHashLookup]")
            throw e
        }
    }

    fun getScopeSpecification(uuid: UUID): ScopeSpecification = metadataQueryService.scopeSpecification(ScopeSpecificationRequest.newBuilder()
            .setSpecificationId(uuid.toString())
            .build()
        ).scopeSpecification.specification
}

fun Collection<Message>.toTxBody(): TxBody = TxBody.newBuilder()
    .addAllMessages(map { it.toAny() })
    .build()

fun Message.toTxBody(): TxBody = listOf(this).toTxBody()

fun Message.toAny(typeUrlPrefix: String = "") = Any.pack(this, typeUrlPrefix)

data class GasEstimate(val estimate: Long, val feeAdjustment: Double? = DEFAULT_FEE_ADJUSTMENT) {
    companion object {
        private const val DEFAULT_FEE_ADJUSTMENT = 1.25
        private const val DEFAULT_GAS_PRICE = 1905.00
    }

    private val adjustment = feeAdjustment ?: DEFAULT_FEE_ADJUSTMENT
    private var gasMultiplier = 1.0

    fun setGasMultiplier(multiplier: Double) { gasMultiplier = multiplier }

    val limit
        get() = (estimate * adjustment * gasMultiplier).roundUp()
    val fees
        get() = (limit * DEFAULT_GAS_PRICE).roundUp()
}
