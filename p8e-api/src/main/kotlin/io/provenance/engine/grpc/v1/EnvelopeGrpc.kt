package io.provenance.engine.grpc.v1

import com.google.protobuf.Message
import io.grpc.stub.StreamObserver
import io.p8e.grpc.complete
import io.p8e.grpc.observers.QueueingStreamObserverSender
import io.p8e.grpc.publicKey
import io.p8e.proto.ContractScope.*
import io.p8e.proto.ContractScope.EnvelopeError.Type.*
import io.p8e.proto.Envelope.*
import io.p8e.proto.Envelope.EnvelopeEvent.EventType.*
import io.p8e.proto.EnvelopeServiceGrpc.EnvelopeServiceImplBase
import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.proto.PK
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.engine.domain.EventStatus
import io.provenance.engine.extension.error
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.engine.grpc.observers.EnvelopeEventObserver
import io.provenance.engine.service.EnvelopeService
import io.provenance.engine.service.EventService
import io.provenance.engine.service.MailboxService
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.shared.util.P8eMDC
import io.p8e.proto.Util.UUID
import org.jetbrains.exposed.sql.transactions.transaction
import org.lognet.springboot.grpc.GRpcService
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

@GRpcService(interceptors = [JwtServerInterceptor::class, UnhandledExceptionInterceptor::class])
class EnvelopeGrpc(
    private val envelopeService: EnvelopeService,
    private val eventService: EventService,
    private val mailboxService: MailboxService,
    private val affiliateService: AffiliateService
): EnvelopeServiceImplBase() {

    init {
        eventService.registerCallback(Event.ENVELOPE_REQUEST, this::sendRequest)
        eventService.registerCallback(Event.ENVELOPE_RESPONSE, this::sendResponse)
        eventService.registerCallback(Event.ENVELOPE_ERROR, this::sendError)
    }

    private val log = logger()
    private val queuers = ConcurrentHashMap<EnvelopeObserverKey, QueueingStreamObserverSender<EnvelopeEvent>>()

    // Only called by [HeartbeatReaper]
    fun removeObserver(
        publicKey: PublicKey,
        classname: String
    ) {
        queuers.remove(
            EnvelopeObserverKey(
                publicKey,
                classname
            )
        )
    }

    override fun getAllByGroupUuid(
        groupUuid: UUID,
        responseObserver: StreamObserver<EnvelopeCollection>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        transaction {
            EnvelopeRecord.findByGroupUuid(groupUuid.toUuidProv())
                .filter { it.scope.publicKey.toJavaPublicKey() == publicKey() }
                .map { if (it.data.hasResult()) it.data.result.toBuilder().build() else it.data.input.toBuilder().build() }
                .let { EnvelopeCollection.newBuilder().addAllEnvelopes(it).build() }
        }.complete(responseObserver)
    }

    override fun getByExecutionUuid(
        executionUuid: UUID,
        responseObserver: StreamObserver<Envelope>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        transaction {
            val publicKey = affiliateService.getSigningKeyPair(publicKey())
            EnvelopeRecord.findByPublicKeyAndExecutionUuid(publicKey.public, executionUuid.toUuidProv())
                ?.let { if (it.data.hasResult()) it.data.result.toBuilder().build() else it.data.input.toBuilder().build() }
                .orThrowNotFound("Envelope not found for execution ${executionUuid.value}")
        }.complete(responseObserver)
    }

    override fun getScopeByExecutionUuid(
        executionUuid: UUID,
        responseObserver: StreamObserver<Scope>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        transaction {
            EnvelopeRecord.findByPublicKeyAndExecutionUuid(publicKey(), executionUuid.toUuidProv())
                ?.scopeSnapshot
                .orThrowNotFound("Scope snapshot not found for contract execution ${executionUuid.value}")
        }.complete(responseObserver)
    }

    override fun rejectByExecutionUuid(
        reject: RejectCancel,
        responseObserver: StreamObserver<Envelope>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        val executionUuid = reject.executionUuid
        transaction {
            EnvelopeRecord.findByPublicKeyAndExecutionUuid(publicKey(), executionUuid.toUuidProv())
                .orThrowNotFound("Envelope not found for execution ${executionUuid.value}")
                .also { record ->
                    val envelope = if (record.data.hasResult()) record.data.result else record.data.input
                    val error = envelope.error(
                        "Contract execution was rejected by affiliate ${publicKey().toHex()}: ${reject.message}",
                        CONTRACT_REJECTED
                    )
                    envelopeService.error(publicKey(), error)
                    mailboxService.error(publicKey(), envelope, error)
                }.let { if (it.data.hasResult()) it.data.result.toBuilder().build() else it.data.input.toBuilder().build() }
        }.complete(responseObserver)
    }

    override fun cancelByExecutionUuid(
        cancel: RejectCancel,
        responseObserver: StreamObserver<Envelope>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        val executionUuid = cancel.executionUuid
        transaction {
            EnvelopeRecord.findByPublicKeyAndExecutionUuid(publicKey(), executionUuid.toUuidProv())
                .orThrowNotFound("Envelope not found for execution ${executionUuid.value}")
                .takeIf { it.isInvoker == true }
                .orThrow { IllegalArgumentException("Contract can only be cancelled by the contract invoker.") }
                .also { record ->
                    val envelope = if (record.data.hasResult()) record.data.result else record.data.input
                    val error = envelope.error(
                        "Contract execution was cancelled by Account Public Key ${publicKey().toHex()}: ${cancel.message}",
                        CONTRACT_CANCELLED
                    )
                    envelopeService.error(publicKey(), error)
                    mailboxService.error(publicKey(), envelope, error)
                }.let { if (it.data.hasResult()) it.data.result.toBuilder().build() else it.data.input.toBuilder().build() }
        }.complete(responseObserver)
    }

    override fun event(outObserver: StreamObserver<EnvelopeEvent>): StreamObserver<EnvelopeEvent> {
        return EnvelopeEventObserver(
            this,
            queuers,
            QueueingStreamObserverSender(outObserver),
            eventService,
            affiliateService
        )
    }

    override fun execute(request: EnvelopeEvent, responseObserver: StreamObserver<EnvelopeEvent>) = try {
        P8eMDC.set(request, clear = true)

        when (request.action) {
            EnvelopeEvent.Action.EXECUTE -> transaction { handleExecute(request) }
            EnvelopeEvent.Action.EXECUTE_FRAGMENT -> transaction { handleExecuteFragment(request) }
            else -> throw IllegalStateException("Unknown action received by server ${request.action.name}")
        }.complete(responseObserver)
    } catch (t: Throwable) {
        log.warn("Envelope execution error: ${t.message}")

        request.toBuilder()
            .setEvent(ENVELOPE_EXECUTION_ERROR)
            .setError(request.envelope.error(t.toMessageWithStackTrace(), NO_ERROR_TYPE))
            .build()
            .complete(responseObserver)
    }

    fun handleAck(event: EnvelopeEvent) {
        // If this is an error not associated with an envelope NOOP
        if (event.envelope.executionUuid == UUID.getDefaultInstance()) {
            return
        }

        val publicKey = event.publicKey.signingPublicKey.toPublicKey()

        when (event.event) {
            ENVELOPE_ACCEPTED -> log.info("Envelope acceptance ACK'd") // deprecated - remove once we know nobody is using an older version of p8e-sdk with the accepted handler
            ENVELOPE_REQUEST -> envelopeService.read(publicKey, event.envelope.executionUuid.toUuidProv())
            ENVELOPE_RESPONSE -> envelopeService.complete(publicKey, event.envelope.executionUuid.toUuidProv())
            ENVELOPE_ERROR -> envelopeService.read(
                publicKey,
                event.envelope.executionUuid.toUuidProv(),
                event.error.uuid.toUuidProv()
            )
            else -> throw IllegalStateException("Unable to ACK on EnvelopeEvent ${event.event.name}")
        }
    }

    fun handleExecute(
        event: EnvelopeEvent
    ): EnvelopeEvent {
        require(event.envelope != Envelope.getDefaultInstance()) {
            "Unable to execute non-initialized envelope"
        }

        val publicKey = if (event.publicKey.hasEncryptionPublicKey()) {
            event.publicKey.encryptionPublicKey.toPublicKey()
        } else {
            event.publicKey.signingPublicKey.toPublicKey()
        }
        val affiliateRecord = affiliateService.get(publicKey)

        val envRecord = envelopeService.handle(
            affiliateRecord!!.publicKey.value.toJavaPublicKey(),
            event.envelope
        )

        val event = EnvelopeEvent.newBuilder()
            .setEvent(ENVELOPE_ACCEPTED)
            .setClassname(envRecord.data.contractClassname)
            .setPublicKey(
                PK.SigningAndEncryptionPublicKeys.newBuilder()
                    .setSigningPublicKey(affiliateRecord.publicKey.value.toPublicKeyProto())
                    .setEncryptionPublicKey(affiliateRecord.encryptionPublicKey.toPublicKeyProto())
            )
            .setEnvelope(envRecord.data.result)
            .build()

        return event
    }

    fun handleExecuteFragment(
        event: EnvelopeEvent
    ): EnvelopeEvent {
        val publicKey = event.publicKey.signingPublicKey.toHex().toPublicKeyProtoSet(affiliateService)

        require(event.envelope.executionUuid != UUID.getDefaultInstance()) {
            "Unable to execute fragment without execution uuid"
        }
        require(event.publicKey != null) {
            "Unable to execute envelope without public key"
        }

        val envRecord = envelopeService.execute(
            publicKey.signingPublicKey.toPublicKey(),
            event.envelope.executionUuid.toUuidProv()
        )

        val event = EnvelopeEvent.newBuilder()
            .setEvent(ENVELOPE_ACCEPTED)
            .setClassname(envRecord.data.contractClassname)
            .setPublicKey(
                PK.SigningAndEncryptionPublicKeys.newBuilder()
                    .setSigningPublicKey(publicKey.signingPublicKey.toPublicKey().toPublicKeyProto())
                    .setEncryptionPublicKey(publicKey.encryptionPublicKey.toPublicKey().toPublicKeyProto())
            )
            .setEnvelope(envRecord.data.result)
            .build()

        val observerKey = event.toEnvelopeObserverKey()
        queuers[observerKey]?.also {
            log.debug("Queuing ${event.event.name} for observer key ${observerKey.description()}")
            it.queue(event)
        } ?: log.debug("Observer not available ${observerKey.description()}")

        return event
    }

    fun sendRequest(
        event: P8eEvent
    ): EventStatus {
        val envelopeUuid = UUID.parseFrom(event.message).toUuidProv()

        val envelope = transaction {
            EnvelopeRecord.findById(envelopeUuid)
                .orThrowNotFound("Envelope not found for uuid $envelopeUuid")
        }

        P8eMDC.set(envelope, clear = true)

        val envelopeEvent = EnvelopeEvent.newBuilder()
            .setEvent(ENVELOPE_REQUEST)
            .setClassname(envelope.data.contractClassname)
            .setPublicKey(
                PK.SigningAndEncryptionPublicKeys.newBuilder()
                    .setSigningPublicKey(envelope.publicKey.toPublicKeyProto())
                    .build()
            )
            .setEnvelope(envelope.data.input.toBuilder().build())
            .build()

        val observerKey = envelopeEvent.toEnvelopeObserverKey()
        return queuers[observerKey]
            ?.let { queuer ->
                log.debug("Queuing ${envelopeEvent.event.name} for observer key ${observerKey.description()}")
                queuer.queue(envelopeEvent)
                null
            } ?: EventStatus.ERROR
    }

    fun sendResponse(
        event: P8eEvent
    ): EventStatus {
        val envelopeUuid = UUID.parseFrom(event.message).toUuidProv()

        val envelope = transaction {
            EnvelopeRecord.findById(envelopeUuid)
                .orThrowNotFound("Envelope not found for uuid $envelopeUuid")
        }

        P8eMDC.set(envelope, clear = true)

        val envelopeEvent = EnvelopeEvent.newBuilder()
            .setEvent(ENVELOPE_RESPONSE)
            .setClassname(envelope.data.contractClassname)
            .setPublicKey(
                PK.SigningAndEncryptionPublicKeys.newBuilder()
                    .setSigningPublicKey(envelope.publicKey.toPublicKeyProto())
                    .build()
            )
            .setEnvelope(envelope.data.result.toBuilder().build())
            .build()

        val observerKey = envelopeEvent.toEnvelopeObserverKey()
        return queuers[observerKey]
            ?.let { queuer ->
                log.debug("Queuing ${envelopeEvent.event.name} for observer key ${observerKey.description()}")
                queuer.queue(envelopeEvent)
                null
            } ?: EventStatus.ERROR
    }

    fun sendError(
        event: P8eEvent
    ): EventStatus {
        val envWithError = EnvelopeUuidWithError.parseFrom(event.message)

        val envelope = transaction {
            EnvelopeRecord.findById(envWithError.envelopeUuid.toUuidProv())
                .orThrowNotFound("Envelope not found for uuid ${envWithError.envelopeUuid.value}")
        }

        P8eMDC.set(envelope, clear = true)

        val envelopeEvent = EnvelopeEvent.newBuilder()
            .setEvent(ENVELOPE_ERROR)
            .setClassname(envelope.data.contractClassname)
            .setPublicKey(
                PK.SigningAndEncryptionPublicKeys.newBuilder()
                    .setSigningPublicKey(envelope.publicKey.toPublicKeyProto())
                    .build()
            )
            .setEnvelope(envelope.data.input.toBuilder().build())
            .setError(envWithError.error.toBuilder().build())
            .build()

        val observerKey = envelopeEvent.toEnvelopeObserverKey()
        return queuers[observerKey]
            ?.let { queuer ->
                log.debug("Queuing ${envelopeEvent.event.name} for observer key ${observerKey.description()}")
                queuer.queue(envelopeEvent)
                null
            } ?: EventStatus.ERROR
    }
}

fun String.toPublicKeyProtoSet(affiliateService: AffiliateService): SigningAndPublicKey {
    return SigningAndPublicKey(
        affiliateService.getEncryptionKeyPair(this.toJavaPublicKey()).public.toPublicKeyProto(),
        affiliateService.getSigningKeyPair(this.toJavaPublicKey()).public.toPublicKeyProto()
    )
}

data class SigningAndPublicKey(
    val encryptionPublicKey: PK.PublicKey,
    val signingPublicKey: PK.PublicKey
)

data class EnvelopeObserverKey(
    val publicKey: PublicKey,
    val classname: String
)
 fun EnvelopeObserverKey.description(): String = "${this.publicKey}:${this.classname}"

fun EnvelopeEvent.toEnvelopeObserverKey(): EnvelopeObserverKey =
    EnvelopeObserverKey(publicKey.signingPublicKey.toPublicKey(), classname)

fun Message.toEvent(event: Event): P8eEvent =
    P8eEvent.newBuilder()
        .setEvent(event)
        .setMessage(toByteString())
        .build()
