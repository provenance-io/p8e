package io.provenance.engine.service

import cosmos.auth.v1beta1.Auth
import cosmos.bank.v1beta1.Tx
import cosmos.base.abci.v1beta1.Abci
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.tx.v1beta1.ServiceOuterClass
import io.p8e.crypto.Hash
import io.p8e.util.orThrow
import io.p8e.util.orThrowNotFound
import io.p8e.util.toHex
import io.p8e.util.toJavaPrivateKey
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toPublicKey
import io.provenance.engine.config.ObjectStoreLocatorProperties
import io.provenance.engine.crypto.Bech32
import io.provenance.engine.crypto.JavaSignerMeta
import io.provenance.engine.crypto.toBech32Data
import io.provenance.metadata.v1.MsgBindOSLocatorRequest
import io.provenance.metadata.v1.ObjectStoreLocator
import io.provenance.p8e.shared.config.ChaincodeProperties
import io.provenance.p8e.shared.crypto.Account
import io.provenance.p8e.shared.domain.AffiliateRecord
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import p8e.Jobs
import java.lang.IllegalStateException
import java.security.KeyPair
import java.security.PublicKey

@Service
class OSLocatorChaincodeService(
    private val chaincodeProperties: ChaincodeProperties,
    private val p8eAccount: Account,
    private val provenanceGrpcService: ProvenanceGrpcService,
    private val objectStoreLocatorProperties: ObjectStoreLocatorProperties,
    private val affiliateService: AffiliateService,
    private val chaincodeInvokeService: ChaincodeInvokeService,
) : JobHandlerService {
    private val log = logger()

    override fun handle(payload: Jobs.P8eJob) {
        val job = payload.addAffiliateOSLocator
        val publicKey = job.publicKey.toPublicKey()
        val affiliateAddress = publicKey.toBech32Address(chaincodeProperties.mainNet)

        logger().info("Handling os locator job for public key ${publicKey.toHex()}")

        val affiliate = transaction { affiliateService.get(publicKey) }.orThrowNotFound("Affiliate with public key ${publicKey.toHex()} not found")
        val affiliateKeyPair = KeyPair(affiliate.publicKey.value.toJavaPublicKey(), affiliate.privateKey.toJavaPrivateKey())

        // estimate amount of hash needed for locator request
        val p8eAccountInfo = provenanceGrpcService.accountInfo(p8eAccount.bech32Address())
        val osLocatorEstimate = estimateLocatorRequestFee(p8eAccountInfo)

        // perform and wait for hash transfer to complete
        waitForTx {
            transferHash(affiliateAddress, osLocatorEstimate.fees)
        }

        // perform and wait for os locator request to complete
        waitForTx {
            recordOSLocator(affiliateKeyPair, affiliateAddress, osLocatorEstimate)
        }
    }

    fun estimateLocatorRequestFee(account: Auth.BaseAccount): GasEstimate = osLocatorMessage(account).let {
        provenanceGrpcService.estimateTx(it.toTxBody(), account.accountNumber, account.sequence).also {
            log.info("OS Locator estimated fees = ${it.fees}")
        }
    }

    fun transferHash(toAddress: String, amount: Long) = Tx.MsgSend.newBuilder()
        .addAllAmount(listOf(CoinOuterClass.Coin.newBuilder()
            .setAmount(amount.toString()) // todo: No clue how much to transfer for initial OS Locator message...
            .setDenom("nhash")
            .build()
        )).setFromAddress(p8eAccount.bech32Address())
        .setToAddress(toAddress)
        .build().let {
            chaincodeInvokeService.batchTx(it.toTxBody())
        }

    fun recordOSLocator(affiliateKeyPair: KeyPair, affiliateAddress: String, gasEstimate: GasEstimate): ServiceOuterClass.BroadcastTxResponse {
        val accountInfo = provenanceGrpcService.accountInfo(affiliateAddress)
        val message = osLocatorMessage(accountInfo)
        return provenanceGrpcService.batchTx(message.toTxBody(), accountInfo.accountNumber, accountInfo.sequence, gasEstimate, JavaSignerMeta(affiliateKeyPair)).also {
            log.info("recordOSLocator response $it")
        }
    }

    fun osLocatorMessage(account: Auth.BaseAccount) = MsgBindOSLocatorRequest.newBuilder()
        .setLocator(ObjectStoreLocator.newBuilder()
            .setOwner(account.address)
            .setLocatorUri(objectStoreLocatorProperties.url)
            // todo: add in p8e owner address
        )
        .build()

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
