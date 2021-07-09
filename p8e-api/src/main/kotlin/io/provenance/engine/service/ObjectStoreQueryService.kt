package io.provenance.engine.service

import com.google.protobuf.Message
import io.grpc.ManagedChannelBuilder
import io.p8e.util.toByteString
import io.provenance.engine.config.ChaincodeProperties
import io.provenance.objectstore.locator.ObjectStoreLocatorServiceGrpc
import io.provenance.objectstore.locator.OsLocator
import io.provenance.objectstore.locator.Util
import io.provenance.os.util.toPublicKeyProtoOS
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jetbrains.exposed.sql.transactions.transaction
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
    fun getObjectStoreUri(publicKey: PublicKey, requesterPublicKey: PublicKey) =
        affiliateService.getAddress(publicKey, chaincodeProperties.mainNet).let { address ->
            provenanceGrpcService.getOSLocatorByAddress(address).locatorUri
        }.let { locatorUri ->
            val signer = transaction { affiliateService.getSigner(requesterPublicKey) }

            OsLocator.GetObjectStoreIPRequest.newBuilder()
                .setOwnerPublicKey(publicKey.toPublicKeyProtoOS().run { Util.PublicKey.parseFrom(toByteString()) })
                .setRequesterPublicKey(signer.getPublicKey().toPublicKeyProtoOS().run { Util.PublicKey.parseFrom(toByteString()) })
                .build().let {
                    ObjectStoreLocatorServiceGrpc.newBlockingStub(locatorUri.toChannel())
                        .getObjectStoreIP(OsLocator.SignedGetObjectStoreIPRequest.newBuilder()
                            .setRequest(it)
                            .setSignature(Util.Signature.newBuilder().setSignature(signer.sign(it).signatureBytes))
                            .build())
                }.objectStoreUri.also {
                    logger().info("fetched objectStoreUri = $it")
                }
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
}
