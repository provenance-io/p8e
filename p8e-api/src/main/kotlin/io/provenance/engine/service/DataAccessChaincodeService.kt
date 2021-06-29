package io.provenance.engine.service

import cosmos.bank.v1beta1.Tx
import cosmos.base.abci.v1beta1.Abci
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.tx.v1beta1.ServiceOuterClass
import io.p8e.crypto.Hash
import io.p8e.util.*
import io.provenance.engine.crypto.toSignerMeta
import io.provenance.engine.config.ChaincodeProperties
import io.provenance.engine.crypto.Account
import io.provenance.engine.crypto.Bech32
import io.provenance.engine.crypto.toBech32Data
import io.provenance.p8e.shared.extension.logger
import io.provenance.metadata.v1.*
import io.provenance.p8e.shared.service.AffiliateService
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import p8e.Jobs
import java.security.KeyPair
import java.security.PublicKey

@Service
class DataAccessChaincodeService(
    private val chaincodeProperties: ChaincodeProperties,
    private val p8eAccount: Account,
    private val provenanceGrpcService: ProvenanceGrpcService,
    private val affiliateService: AffiliateService,
    private val chaincodeInvokeService: ChaincodeInvokeService,
) : JobHandlerService {
    private val log = logger()

    override fun handle(payload: Jobs.P8eJob) {
        val msgAddScopeDataAccessRequest = MsgAddScopeDataAccessRequest.newBuilder().addAllDataAccess(payload.msgAddScopeDataAccessRequest.dataAccessList)
            .addAllSigners(payload.msgAddScopeDataAccessRequest.signersList)
            .setScopeId(payload.msgAddScopeDataAccessRequest.scopeId)
            .build()
        val publicKey = payload.msgAddScopeDataAccessRequest.publicKey.toPublicKey()

        val affiliate =
            transaction { affiliateService.get(publicKey) }.orThrowNotFound("Affiliate with public key ${publicKey.toHex()} not found")
        val affiliateKeyPair =
            KeyPair(affiliate.publicKey.value.toJavaPublicKey(), affiliate.privateKey.toJavaPrivateKey())

        // Estimate transaction cost
        val affiliateAddress = publicKey.toBech32Address(chaincodeProperties.mainNet)
        val affiliateAccount = provenanceGrpcService.accountInfo(affiliateAddress)

        val estimate = provenanceGrpcService.estimateTx(
                msgAddScopeDataAccessRequest.toTxBody(),
                affiliateAccount.accountNumber,
                affiliateAccount.sequence
            )

        // perform and wait for hash transfer to complete if account has less than 10 hash
        if (provenanceGrpcService.getAccountCoins(affiliateAddress)[0].amount.toLong() / 1000000000 < 10) {
            waitForTx {
                transferHash(affiliateAccount.address, 10000000000)
            }
        }
        val resp = provenanceGrpcService.batchTx(
            msgAddScopeDataAccessRequest.toTxBody(),
            affiliateAccount.accountNumber,
            affiliateAccount.sequence,
            estimate,
            affiliateKeyPair.toSignerMeta()
        )
        if (resp.txResponse.code != 0) {
            // adding extra raw logging during exceptional cases so that we can see what typical responses look like while this interface is new
            log.info("Abci.TxResponse from chain ${resp.txResponse}")

            val errorMessage = "${resp.txResponse.code} - ${resp.txResponse.rawLog}"

            throw IllegalStateException(errorMessage)
        }
    }

    fun transferHash(toAddress: String, amount: Long) = Tx.MsgSend.newBuilder()
        .addAllAmount(listOf(
            CoinOuterClass.Coin.newBuilder()
            .setAmount(amount.toString())
            .setDenom("nhash")
            .build()
        )).setFromAddress(p8eAccount.bech32Address())
        .setToAddress(toAddress)
        .build().let {
            chaincodeInvokeService.batchTx(it.toTxBody())
        }

    fun waitForTx(block: () -> ServiceOuterClass.BroadcastTxResponse): Abci.TxResponse {
        val txResponse = block()
        val txHash = txResponse.txResponse.txhash

        if (txResponse.txResponse.code != 0) {
            throw Exception("Error submitting transaction [code = ${txResponse.txResponse.code}, codespace = ${txResponse.txResponse.codespace}, raw_log = ${txResponse.txResponse.rawLog}]")
        }

        val maxAttempts = 5
        log.info("Waiting for transaction to complete [hash = $txHash]")
        for (i in 1 .. maxAttempts) {
            Thread.sleep(2500)
            val response = try {
                provenanceGrpcService.getTx(txHash)
            } catch (t: Throwable) {
                log.info("Error fetching transaction [hash = $txHash, message = ${t.message}]")
                continue
            }

            when {
                response.code == 0 -> {
                    log.info("Transaction complete [hash = $txHash]")
                    return response
                }
                response.code > 0 -> throw Exception("Transaction Failed with log ${response.rawLog}")
                else -> continue // todo: what are the failure conditions, non-0 code... tx not found... under which conditions might it eventually succeed, not found needs a retry?
            }
        }
        throw Exception("Failed to fetch transaction after $maxAttempts attempts [hash = $txHash]")
    }

    // todo: this should really be somewhere more shared... but p8e-util where other key conversion extensions are doesn't have the Hash class...
    private fun PublicKey.toBech32Address(mainNet: Boolean): String =
        (this as BCECPublicKey).q.getEncoded(true)
            .let {
                Hash.sha256hash160(it)
            }.let {
                val prefix = if (mainNet) Bech32.PROVENANCE_MAINNET_ACCOUNT_PREFIX else Bech32.PROVENANCE_TESTNET_ACCOUNT_PREFIX
                it.toBech32Data(prefix).address
            }
}
