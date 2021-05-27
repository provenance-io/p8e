package io.provenance.p8e.shared.service

import com.google.protobuf.Message
import io.grpc.ManagedChannelBuilder
import io.p8e.util.ECKeyConverter.toJavaPrivateKey
import io.p8e.util.toByteString
import io.provenance.objectstore.locator.ObjectStoreLocatorServiceGrpc
import io.provenance.objectstore.locator.OsLocator
import io.provenance.objectstore.locator.Util
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.shared.config.ObjectStoreLocatorProperties
import io.provenance.p8e.shared.crypto.Account
import io.provenance.pbc.clients.StdSignature
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.stereotype.Service
import java.net.URI
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.concurrent.TimeUnit

@Service
class OSLocatorService(
    objectStoreLocatorProperties: ObjectStoreLocatorProperties?,
    accountProvider: Account
) {
    private val channel = objectStoreLocatorProperties?.url?.let { URI(it) }?.let { uri ->
        ManagedChannelBuilder.forAddress(uri.host, uri.port)
            .also {
                if (uri.scheme == "grpcs") {
                    it.useTransportSecurity()
                } else {
                    it.usePlaintext()
                }
            }
            .build()
    }

    private val objectStoreLocator = channel?.let { ObjectStoreLocatorServiceGrpc.newBlockingStub(it) }
    private val privateKey = accountProvider.getECKeyPair().privateKey.toJavaPrivateKey()

    fun registerAffiliate(publicKey: PublicKey) = objectStoreLocator?.run {
        val request = OsLocator.AssociateOwnerAddressRequest.newBuilder()
            .setOwnerPublicKey(Util.PublicKey.newBuilder()
                .setSecp256K1(ECUtils.convertPublicKeyToBytes(publicKey).toByteString())
                .build())
            .build()

        associateOwnerAddress(OsLocator.SignedAssociateOwnerAddressRequest.newBuilder()
            .setRequest(request)
            .setSignature(Util.Signature.newBuilder()
                .setSignature(request.sign().toByteString())
                .build())
            .build())
    }

    private fun Message.sign(): ByteArray = Signature.getInstance("SHA512withECDSA", BouncyCastleProvider.PROVIDER_NAME).run {
        initSign(privateKey)
        update(toByteArray())
        sign()
    }
}
