package io.p8e.client

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.protobuf.Message
import com.google.protobuf.util.Timestamps
import io.grpc.ManagedChannel
import io.grpc.stub.StreamObserver
import io.p8e.engine.extension.withAudience
import io.p8e.grpc.client.*
import io.p8e.index.client.IndexClient
import io.p8e.proto.Affiliate
import io.p8e.proto.Affiliate.AffiliateContractWhitelist
import io.p8e.proto.Common.Location
import io.p8e.proto.ContractScope.*
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.Domain.SpecRequest
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.proto.Envelope.EnvelopeEvent.Action
import io.p8e.proto.Index.FactHistoryRequest
import io.p8e.proto.Index.FactHistoryResponse
import io.p8e.proto.PK
import io.p8e.spec.P8eContract
import io.p8e.util.ProtoParseException
import io.p8e.util.orThrow
import io.p8e.util.toPublicKeyProto
import io.p8e.util.toOffsetDateTimeProv
import io.p8e.util.toProtoTimestampProv
import io.p8e.util.toProtoUuidProv
import io.provenance.p8e.encryption.ecies.ECUtils
import java.io.InputStream
import java.lang.reflect.Method
import java.security.PublicKey
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

interface P8eClient {

    fun addSpec(spec: List<ContractSpec>)

    fun register(enc: Affiliate.AffiliateRegisterRequest)

    fun whitelistClass(whitelist: AffiliateContractWhitelist)

    fun getContracts(groupUuid: UUID): EnvelopeCollection

    fun getContract(executionUuid: UUID): Envelope

    fun getScopeSnapshot(executionUuid: UUID): Scope

    fun reject(executionUuid: UUID, message: String): Envelope

    fun cancel(executionUuid: UUID, message: String): Envelope

    fun <T : P8eContract> event(
        clazz: Class<T>,
        inObserver: StreamObserver<EnvelopeEvent>
    ): StreamObserver<EnvelopeEvent>

    fun execute(
        request: EnvelopeEvent
    ): EnvelopeEvent

    fun <T : Message> loadProto(uri: String, clazz: Class<T>): T

    fun <T : Message> saveProto(msg: T, executionUuid: UUID?, audience: Set<PublicKey> = setOf()): Location

    fun storeObject(inputStream: InputStream, audience: Set<PublicKey>): Location

    fun loadObject(hash: String): ByteArray

    fun loadProto(
        hash: String,
        classname: String
    ): Message

    fun loadProto(
        bytes: ByteArray,
        classname: String
    ): Message

    fun loadProtoJson(hash: String, classname: String, contractSpecHash: String): String

    fun getFactHistory(
        scopeUuid: UUID,
        factName: String,
        classname: String,
        startWindow: OffsetDateTime = Timestamps.MIN_VALUE.toOffsetDateTimeProv(),
        endWindow: OffsetDateTime = Timestamps.MAX_VALUE.toOffsetDateTimeProv()
    ): FactHistoryResponse
}

// TODO - envar the threadpool size
abstract class EventMonitorClient(
    protected val publicKey: PublicKey,
    protected val signingAndEncryptionPublicKeysClient: SigningAndEncryptionPublicKeysClient,
    protected val chaincodeClient: ChaincodeClient,
    protected val envelopeClient: EnvelopeClient,
    protected val indexClient: IndexClient,
    protected val objectClient: ObjectClient
): P8eClient {

    override fun addSpec(
        spec: List<ContractSpec>
    ) {
        chaincodeClient.addSpec(
            SpecRequest.newBuilder()
                .addAllSpec(spec)
                .build()
        )
    }

    override fun register(enc: Affiliate.AffiliateRegisterRequest) = signingAndEncryptionPublicKeysClient.register(enc)

    override fun whitelistClass(whitelist: AffiliateContractWhitelist) = signingAndEncryptionPublicKeysClient.whitelistClass(whitelist)

    override fun getContracts(groupUuid: UUID): EnvelopeCollection = envelopeClient.getAllByGroupUuid(groupUuid)

    override fun getContract(executionUuid: UUID): Envelope = envelopeClient.getByExecutionUuid(executionUuid)

    override fun getScopeSnapshot(executionUuid: UUID): Scope = envelopeClient.getScopeByExecutionUuid(executionUuid)

    override fun reject(executionUuid: UUID, message: String): Envelope = envelopeClient.rejectByExecutionUuid(executionUuid, message)

    override fun cancel(executionUuid: UUID, message: String): Envelope = envelopeClient.cancelByExecutionUuid(executionUuid, message)

    override fun <T : P8eContract> event(
        clazz: Class<T>,
        inObserver: StreamObserver<EnvelopeEvent>
    ): StreamObserver<EnvelopeEvent> {
        val outObserver = envelopeClient.event(inObserver)
        val event = EnvelopeEvent.newBuilder()
            .setAction(Action.CONNECT)
            .setPublicKey(
                PK.SigningAndEncryptionPublicKeys.newBuilder()
                    .setSigningPublicKey(publicKey.toPublicKeyProto())
                    .build()
            )
            .setClassname(clazz.name)
            .build()

        // Start subscription by sending affiliate/cert/class
        outObserver.onNext(
            event
        )

        return outObserver
    }

    override fun execute(request: EnvelopeEvent): EnvelopeEvent {
        return envelopeClient.execute(request)
    }

    override fun <T : Message> loadProto(uri: String, clazz: Class<T>): T =
        loadProto(uri, clazz.name) as T

    override fun <T : Message> saveProto(msg: T, executionUuid: UUID?, audience: Set<PublicKey>): Location =
        objectClient.store(msg.withAudience(audience.map(ECUtils::convertPublicKeyToBytes).toSet()))

    override fun loadProto(
        hash: String,
        classname: String
    ): Message {
        return loadProto(
            loadObject(hash),
            classname
        )
    }

    override fun loadProto(
        bytes: ByteArray,
        classname: String
    ): Message {
        val clazz = Message::class.java
        val instanceOfThing = parseFromLookup(classname)
            .invoke(
                null,
                bytes
            )

        if (!clazz.isAssignableFrom(instanceOfThing.javaClass)) {
            throw ProtoParseException("Unable to assign instance ${instanceOfThing::class.java.name} to type ${clazz.name}")
        }
        return clazz.cast(instanceOfThing)
    }

    override fun loadProtoJson(hash: String, classname: String, contractSpecHash: String): String = objectClient.loadJson(hash, classname, contractSpecHash)

    override fun storeObject(
        inputStream: InputStream,
        audience: Set<PublicKey>
    ): Location {
        return objectClient.store(
            inputStream.use {
                it.readAllBytes()
            }.withAudience(audience.map(ECUtils::convertPublicKeyToBytes).toSet())
        )
    }

    override fun loadObject(
        hash: String
    ): ByteArray {
        return byteCache.asMap().computeIfAbsent(hash) {
            objectClient.load(hash)
        }
    }

    override fun getFactHistory(
        scopeUuid: UUID,
        factName: String,
        classname: String,
        startWindow: OffsetDateTime,
        endWindow: OffsetDateTime
    ): FactHistoryResponse {
        return indexClient.factHistory(
            FactHistoryRequest.newBuilder()
                .setScopeUuid(scopeUuid.toProtoUuidProv())
                .setFactName(factName)
                .setClassname(classname)
                .setStartWindow(startWindow.toProtoTimestampProv())
                .setEndWindow(endWindow.toProtoTimestampProv())
                .build()
        )
    }

    companion object {
        private var byteCache: Cache<String, ByteArray> = CacheBuilder.newBuilder()
            .maximumSize(128 * 1000) // 128MB max size assuming facts average under 1KB
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build()
        private var parseFromCache: Cache<String, Method> = CacheBuilder.newBuilder()
            .maximumSize(128 * 1000) // 128MB max size assuming facts average under 1KB
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build()

        private fun parseFromLookup(classname: String): Method {
            return parseFromCache.asMap().computeIfAbsent(classname) {
                javaClass.classLoader.loadClass(classname)
                    .declaredMethods.find {
                        it.returnType.name == classname && it.name == "parseFrom" && it.parameterCount == 1 && it.parameters[0].type == ByteArray::class.java
                    }.orThrow { ProtoParseException("Unable to find \"parseFrom\" method on $classname") }
            }
        }
    }
}

class RemoteClient(
    publicKey: PublicKey,
    channel: ManagedChannel,
    challengeResponseInterceptor: ChallengeResponseInterceptor,
    indexClient: IndexClient
): EventMonitorClient(
    publicKey,
    SigningAndEncryptionPublicKeysClient(channel, challengeResponseInterceptor),
    ChaincodeClient(channel, challengeResponseInterceptor),
    EnvelopeClient(channel, challengeResponseInterceptor),
    indexClient,
    ObjectClient(channel, challengeResponseInterceptor)
)
