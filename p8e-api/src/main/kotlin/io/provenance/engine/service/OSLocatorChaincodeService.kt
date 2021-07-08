package io.provenance.engine.service

import cosmos.auth.v1beta1.Auth
import cosmos.bank.v1beta1.Tx
import cosmos.base.abci.v1beta1.Abci
import cosmos.base.v1beta1.CoinOuterClass
import cosmos.tx.v1beta1.ServiceOuterClass
import io.p8e.crypto.SignerImpl
import io.p8e.util.toHex
import io.p8e.util.toPublicKey
import io.provenance.engine.config.ObjectStoreLocatorProperties
import io.provenance.engine.crypto.toSignerMeta
import io.provenance.metadata.v1.MsgBindOSLocatorRequest
import io.provenance.metadata.v1.ObjectStoreLocator
import io.provenance.engine.config.ChaincodeProperties
import io.provenance.engine.crypto.Account
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import p8e.Jobs

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
        val affiliateAddress = affiliateService.getAddress(publicKey, chaincodeProperties.mainNet)

        logger().info("Handling os locator job for public key ${publicKey.toHex()}")

        val affiliateSigner = transaction { affiliateService.getSigner(publicKey) }.apply {
            hashType = SignerImpl.Companion.HashType.SHA256
            deterministic = true
        }

        // estimate amount of hash needed for locator request
        val p8eAccountInfo = provenanceGrpcService.accountInfo(p8eAccount.bech32Address())
        val osLocatorEstimate = estimateLocatorRequestFee(p8eAccountInfo)

        // perform and wait for hash transfer to complete
        waitForTx {
            transferHash(affiliateAddress, osLocatorEstimate.fees)
        }

        // perform and wait for os locator request to complete
        waitForTx {
            recordOSLocator(affiliateSigner, affiliateAddress, osLocatorEstimate)
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

    fun recordOSLocator(affiliateSigner: SignerImpl, affiliateAddress: String, gasEstimate: GasEstimate): ServiceOuterClass.BroadcastTxResponse {
        val accountInfo = provenanceGrpcService.accountInfo(affiliateAddress)
        val message = osLocatorMessage(accountInfo)
        return provenanceGrpcService.batchTx(message.toTxBody(), accountInfo.accountNumber, accountInfo.sequence, gasEstimate, affiliateSigner.toSignerMeta()).also {
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
}
