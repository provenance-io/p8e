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
import io.p8e.util.NotFoundException
import io.p8e.util.orThrowNotFound
import io.p8e.util.toHex
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toByteString
import io.p8e.util.toJsonString
import io.provenance.engine.extension.toProto
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.os.client.OsClient
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
        val encryptionKeyPair = transaction { affiliateService.getEncryptionKeyPair(publicKey()) }
        val signingKeyPair = transaction { affiliateService.getSigningKeyPair(publicKey()) }
        val affiliateShares = transaction { affiliateService.getSharePublicKeys(request.toAudience().plus(publicKey())) }

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
            .save(encryptionKeyPair, msg, signingKeyPair, audience)
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
            val signaturePublicKey = affiliateService.getSigningKeyPair(publicKey()).public
            val encryptionKeyPair = affiliateService.getEncryptionKeyPair(publicKey())

            DefinitionService(osClient).get(
                encryptionKeyPair,
                request.uri,
                "<raw fetch - classname not included>",
                signaturePublicKey = signaturePublicKey
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
            val signingKeyPair = transaction{ affiliateService.getSigningKeyPair(publicKey()) }
            val encryptionKeyPair = affiliateService.getEncryptionKeyPair(publicKey())
            val spec = DefinitionService(osClient).loadProto(encryptionKeyPair, request.contractSpecHash, ContractSpecs.ContractSpec::class.java.name, signingKeyPair.public) as ContractSpecs.ContractSpec
            val childSpec = spec.findChildSpec(request.classname).orThrowNotFound("${request.classname} spec not found within contract spec with hash ${request.contractSpecHash} for public key ${publicKey().toHex()}")

            val classLoaderKey = "${spec.definition.resourceLocation.ref.hash}-${childSpec.resourceLocation.ref.hash}"
            val memoryClassLoader = ClassLoaderCache.classLoaderCache.computeIfAbsent(classLoaderKey) {
                MemoryClassLoader("", ByteArrayInputStream(ByteArray(0)))
            }

            DefinitionService(osClient, memoryClassLoader).run {
                addJar(encryptionKeyPair, childSpec)

                forThread {
                    loadProto(encryptionKeyPair, request.hash, request.classname, signingKeyPair.public)
                }.toJsonString()
            }
        }?.let { json ->
            ObjectLoadJsonResponse.newBuilder()
                .setJson(json)
                .build()
                .complete(responseObserver)
        }
    }

    /**
     * Find a given DefinitionSpec by className within the ContractSpec's definition, inputs, conditions, or considerations.
     */
    private fun ContractSpecs.ContractSpec.findChildSpec(
        className: String
    ) = listOf(
        listOf(definition),
        inputSpecsList,
        conditionSpecsList.flatMap { it.inputSpecsList + it.outputSpec.spec },
        considerationSpecsList.flatMap { it.inputSpecsList + it.outputSpec.spec }
    ).flatten()
        .firstOrNull { it.resourceLocation.classname == className }
}
