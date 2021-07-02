package io.provenance.engine.grpc.v1

import io.grpc.stub.StreamObserver
import io.p8e.classloader.ClassLoaderCache
import io.p8e.classloader.MemoryClassLoader
import io.p8e.definition.DefinitionService
import io.p8e.engine.extension.toAudience
import io.p8e.grpc.complete
import io.p8e.grpc.publicKey
import io.p8e.proto.Common.Location
import io.p8e.proto.Common.WithAudience
import io.p8e.proto.ContractSpecs
import io.p8e.proto.ObjectGrpc.ObjectImplBase
import io.p8e.proto.Objects
import io.p8e.proto.Objects.ObjectLoadRequest
import io.p8e.proto.Objects.ObjectLoadResponse
import io.p8e.proto.Objects.ObjectLoadJsonResponse
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toByteString
import io.p8e.util.toJsonString
import io.provenance.engine.extension.toProto
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.os.client.OsClient
import io.provenance.p8e.encryption.model.KeyRef
import io.provenance.p8e.shared.util.P8eMDC
import org.jetbrains.exposed.sql.transactions.transaction
import org.lognet.springboot.grpc.GRpcService
import java.io.ByteArrayInputStream

@GRpcService(interceptors = [JwtServerInterceptor::class, UnhandledExceptionInterceptor::class])
class ObjectGrpc(
    private val affiliateService: AffiliateService,
    private val osClient: OsClient
): ObjectImplBase() {

    private val log = logger()

    override fun store(
        request: WithAudience,
        responseObserver: StreamObserver<Location>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        val msg = request.message.toByteArray()
        val signer = transaction { affiliateService.getSigner(publicKey()) }
        val affiliateShares = transaction { affiliateService.getSharePublicKeys(request.toAudience().plus(publicKey())) }
        val encryptionPublicKey = transaction { affiliateService.getEncryptionPublicKey(publicKey())}

        // Update the dime's audience list to use encryption public keys.
        val audience = request.toAudience().plus(affiliateShares.value).map {
            transaction {
                try {
                    affiliateService.getEncryptionKeyPair(it).public
                } catch(t: Throwable) {
                    // if key is not found in the affiliate table just return what was in the request
                    it
                }
            }
        }.toSet()

        DefinitionService(osClient)
            .save(encryptionPublicKey, msg, signer, audience)
            .toProto()
            .complete(responseObserver)
    }

    override fun load(
        request: ObjectLoadRequest,
        responseObserver: StreamObserver<ObjectLoadResponse>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        log.debug("ObjectLoadRequest ${request.uri}")

        transaction {
            val signer = affiliateService.getSigner(publicKey())
            val encryptionKeyRef = affiliateService.getEncryptionKeyRef(publicKey())

            DefinitionService(osClient).get(
                encryptionKeyRef,
                request.uri,
                "<raw fetch - classname not included>",
                signer = signer,
                signaturePublicKey = signer.getPublicKey()
            ).readAllBytes()
        }?.let { bytes ->
            ObjectLoadResponse.newBuilder()
                .setBytes(bytes.toByteString())
                .build()
                .complete(responseObserver)
        }
    }

    override fun loadJson(
        request: Objects.ObjectLoadJsonRequest,
        responseObserver: StreamObserver<ObjectLoadJsonResponse>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        log.debug("ObjectLoadJsonRequest ${request.hash}")

        transaction {
            val signer = affiliateService.getSigner(publicKey())
            val encryptionKeyRef = affiliateService.getEncryptionKeyRef(publicKey())
            val spec = DefinitionService(osClient).loadProto(encryptionKeyRef, request.contractSpecHash, ContractSpecs.ContractSpec::class.java.name, signer, signer.getPublicKey()) as ContractSpecs.ContractSpec

            val classLoaderKey = "${spec.definition.resourceLocation.ref.hash}"
            val memoryClassLoader = ClassLoaderCache.classLoaderCache.computeIfAbsent(classLoaderKey) {
                MemoryClassLoader("", ByteArrayInputStream(ByteArray(0)))
            }

            DefinitionService(osClient, memoryClassLoader).run {
                // spec.definition is the contract uberjar
                addJar(encryptionKeyRef, spec.definition, signer)

                forThread {
                    loadProto(encryptionKeyRef, request.hash, request.classname, signer, signer.getPublicKey())
                }.toJsonString()
            }
        }?.let { json ->
            ObjectLoadJsonResponse.newBuilder()
                .setJson(json)
                .build()
                .complete(responseObserver)
        }
    }
}
