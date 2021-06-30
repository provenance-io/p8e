package io.provenance.engine.service

import com.google.protobuf.Message
import io.grpc.ManagedChannelBuilder
import io.p8e.util.toByteString
import io.provenance.engine.config.ChaincodeProperties
import io.provenance.objectstore.locator.ObjectStoreLocatorServiceGrpc
import io.provenance.objectstore.locator.OsLocator
import io.provenance.objectstore.locator.Util
import io.provenance.os.util.toPublicKeyProtoOS
import io.provenance.p8e.shared.service.AffiliateService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.net.URI
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

@Service
class ObjectStoreQueryService(
    private val chaincodeProperties: ChaincodeProperties,
    private val provenanceGrpcService: ProvenanceGrpcService,
    private val affiliateService: AffiliateService,
) {
    companion object {
        private const val PUBLIC_KEY_TO_IP = "PUBLIC_KEY_TO_IP"
    }

    @Cacheable(PUBLIC_KEY_TO_IP)
    fun getObjectStoreUri(publicKey: PublicKey, requesterKeyPair: KeyPair) =
        affiliateService.getAddress(publicKey, chaincodeProperties.mainNet).let { address ->
            provenanceGrpcService.getOSLocatorByAddress(address).locatorUri
        }.let { locatorUri ->
            OsLocator.GetObjectStoreIPRequest.newBuilder()
                .setOwnerPublicKey(publicKey.toPublicKeyProtoOS().run { Util.PublicKey.parseFrom(toByteString()) })
                .setRequesterPublicKey(requesterKeyPair.public.toPublicKeyProtoOS().run { Util.PublicKey.parseFrom(toByteString()) })
                .build().let {
                    ObjectStoreLocatorServiceGrpc.newBlockingStub(locatorUri.toChannel())
                        .getObjectStoreIP(OsLocator.SignedGetObjectStoreIPRequest.newBuilder()
                            .setRequest(it)
                            .setSignature(Util.Signature.newBuilder().setSignature(it.sign(requesterKeyPair.private).toByteString())) // todo: need to support additional signers once smartkey signer branch merged to main
                            .build())
                }.objectStoreUri
        }

    private fun String.toChannel() = URI.create(this).let { uri ->
        ManagedChannelBuilder.forAddress(uri.host, uri.port)
            .apply {
                if (uri.scheme == "grpcs") {
                    useTransportSecurity()
                } else {
                    usePlaintext()
                }
            }
            .build()
    }

    private fun Message.sign(privateKey: PrivateKey): ByteArray = Signature.getInstance("SHA512withECDSA", BouncyCastleProvider.PROVIDER_NAME).run {
        initSign(privateKey)
        update(toByteArray())
        sign()
    }
}
