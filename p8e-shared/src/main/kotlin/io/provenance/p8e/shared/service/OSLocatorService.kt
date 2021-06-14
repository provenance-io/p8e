package io.provenance.p8e.shared.service

import com.google.protobuf.Message
import io.grpc.ManagedChannelBuilder
import io.p8e.util.ECKeyConverter.toJavaPrivateKey
import io.p8e.util.toByteString
import io.p8e.util.toPublicKeyProto
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.shared.crypto.Account
import io.provenance.p8e.shared.domain.JobRecord
import io.provenance.pbc.clients.StdSignature
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import p8e.Jobs
import java.net.URI
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.concurrent.TimeUnit

@Service
class OSLocatorService(
    accountProvider: Account
) {
    private val privateKey = accountProvider.getECKeyPair().privateKey.toJavaPrivateKey() // todo: remove w/ chaincode properties

    fun registerAffiliate(publicKey: PublicKey) = transaction {
        JobRecord.create(Jobs.P8eJob.newBuilder()
            .setAddAffiliateOSLocator(Jobs.AddAffiliateOSLocator.newBuilder()
                .setPublicKey(publicKey.toPublicKeyProto())
            )
            .build())
    }

    private fun Message.sign(): ByteArray = Signature.getInstance("SHA512withECDSA", BouncyCastleProvider.PROVIDER_NAME).run {
        initSign(privateKey)
        update(toByteArray())
        sign()
    }
}
