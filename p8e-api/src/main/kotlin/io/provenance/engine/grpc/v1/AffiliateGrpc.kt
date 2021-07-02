package io.provenance.engine.grpc.v1

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import io.p8e.grpc.complete
import io.p8e.grpc.publicKey
import io.p8e.proto.Affiliate
import io.p8e.proto.Affiliate.AffiliateContractWhitelist
import io.p8e.proto.Affiliate.AffiliateSharesResponse
import io.p8e.proto.AffiliateServiceGrpc.AffiliateServiceImplBase
import io.p8e.util.computePublicKey
import io.p8e.util.toHex
import io.p8e.util.toPrivateKey
import io.p8e.util.toPublicKeyProto
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.shared.util.P8eMDC
import org.jetbrains.exposed.sql.transactions.transaction
import org.lognet.springboot.grpc.GRpcService
import java.security.KeyPair

@GRpcService(interceptors = [JwtServerInterceptor::class, UnhandledExceptionInterceptor::class])
class AffiliateGrpc(
    private val affiliateService: AffiliateService,
): AffiliateServiceImplBase() {

    private val log = logger()

    override fun register(
        request: Affiliate.AffiliateRegisterRequest,
        responseObserver: StreamObserver<Empty>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        val privateKey = request.signingPrivateKey.toPrivateKey()
        val publicKey = privateKey.computePublicKey()

        val ecPrivateKey = if(request.encryptionPrivateKey.isInitialized) request.encryptionPrivateKey.toPrivateKey() else privateKey
        val ecPublicKey = ecPrivateKey.computePublicKey()

        log.info("Saving affiliate encryption key: ${ecPublicKey.toHex()}")

        transaction {
            affiliateService.save(
                signingKeyPair = KeyPair(publicKey, privateKey),
                encryptionKeyPair = KeyPair(ecPublicKey, ecPrivateKey)
            )
        }
        responseObserver.complete()
    }

    override fun shares(
        request: Empty,
        responseObserver: StreamObserver<AffiliateSharesResponse>
    ) {
        // TODO public key needs to be the authenticated public key
        val sharePublicKeys = transaction { affiliateService.getShares(publicKey()).map { it.publicKey } }

        responseObserver.onNext(
            AffiliateSharesResponse.newBuilder()
                .addAllShares(sharePublicKeys.map { it.toPublicKeyProto() })
                .build()
        )
        responseObserver.onCompleted()
    }

    override fun whitelistClass(
        request: AffiliateContractWhitelist,
        responseObserver: StreamObserver<Empty>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        log.info("Saving affiliate whitelist classname: ${request.classname}")
        transaction { affiliateService.addWhitelistClass(publicKey(), request) }
        responseObserver.complete()
    }
}
