package io.provenance.engine.grpc.observers

import io.grpc.stub.StreamObserver
import io.p8e.grpc.clientIp
import io.p8e.grpc.observers.CompleteState
import io.p8e.grpc.observers.EndState
import io.p8e.grpc.observers.ExceptionState
import io.p8e.grpc.observers.NullState
import io.p8e.grpc.observers.QueueingStreamObserverSender
import io.p8e.proto.ContractScope.EnvelopeError
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.proto.Envelope.EnvelopeEvent.Action
import io.p8e.proto.Envelope.EnvelopeEvent.Action.*
import io.p8e.proto.Envelope.EnvelopeEvent.EventType
import io.p8e.proto.PK
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.domain.AffiliateConnectionRecord
import io.provenance.engine.domain.ConnectionStatus.CONNECTED
import io.provenance.p8e.shared.util.P8eMDC
import io.provenance.engine.extension.error
import io.provenance.engine.grpc.interceptors.statusRuntimeException
import io.provenance.engine.grpc.v1.*
import io.provenance.engine.service.EventService
import io.provenance.p8e.shared.service.AffiliateService
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

class EnvelopeEventObserver(
    private val envelopeGrpc: EnvelopeGrpc,
    private val queuers: ConcurrentHashMap<EnvelopeObserverKey, QueueingStreamObserverSender<EnvelopeEvent>>,
    private val queuer: QueueingStreamObserverSender<EnvelopeEvent>,
    private val eventService: EventService,
    private val affiliateService: AffiliateService
): StreamObserver<EnvelopeEvent> {

    private val connectedKey = AtomicReference<PK.PublicKey>(PK.PublicKey.getDefaultInstance())
    private var queuerKey: EnvelopeObserverKey? = null

    override fun onNext(value: EnvelopeEvent) {
        P8eMDC.set(value, clear = true)

        if (value.classname.isNullOrBlank()) {
            queuer.error(
                AffiliateConnectionException(
                    "Unable to listen for events with requested classname: [${value.classname}]"
                ).statusRuntimeException()
            )
            return
        }

        val publicKeyProto = try {
            transaction {
                value.publicKey.signingPublicKey.toHex().toPublicKeyProtoSet(affiliateService)
            }
        } catch (t: Throwable) {
            SigningAndPublicKey(
                encryptionPublicKey = PK.PublicKey.getDefaultInstance(),
                signingPublicKey = PK.PublicKey.getDefaultInstance()
            )
        }

        if (publicKeyProto.encryptionPublicKey == PK.PublicKey.getDefaultInstance() || publicKeyProto.signingPublicKey == PK.PublicKey.getDefaultInstance()) {
            queuer.error(
                AffiliateConnectionException(
                    """
                        Unable to find affiliate with requested signing public key: [${value.publicKey.signingPublicKey.toHex()}].
                        Fetched affiliate - signing: [${publicKeyProto.signingPublicKey.toHex()}] encryption: [${publicKeyProto.encryptionPublicKey.toHex()}]
                    """.trimIndent()
                ).statusRuntimeException()
            )
            return
        }

        val event = EnvelopeEvent.newBuilder()
            .setEvent(value.event)
            .setClassname(value.classname)
            .setPublicKey(PK.SigningAndEncryptionPublicKeys.newBuilder()
                .setSigningPublicKey(publicKeyProto.signingPublicKey)
                .setEncryptionPublicKey(publicKeyProto.encryptionPublicKey)
            )
            .setEnvelope(value.envelope)
            .build()

        if (queuerKey == null) {
            queuerKey = event.toEnvelopeObserverKey()
        }

        val existingQueuer = queuers[queuerKey!!] ?: run { connect(value) }
        if (existingQueuer != queuer) {
            queuer.error(
                AffiliateConnectionException(
                    "Unable to listen for the same public key [${queuerKey!!.publicKey.toHex()}] and classname [${value.classname}] combo more than once."
                ).statusRuntimeException()
            )
            return
        }

        if (value.action != CONNECT) {
            if (connectedKey.get() != publicKeyProto.signingPublicKey && connectedKey.get() != publicKeyProto.encryptionPublicKey) {
                queuer.error(AuthenticationException("Unauthorized access").statusRuntimeException())
                return
            }

            when (value.action) {
                ACK -> thread(ActionExecutors.ACK.executor, queuer, value) {
                    P8eMDC.set(value, clear = true)
                    transaction { envelopeGrpc.handleAck(value) }
                }
                EXECUTE -> thread(ActionExecutors.EXECUTE.executor, queuer, value) {
                    P8eMDC.set(value, clear = true)
                    transaction { envelopeGrpc.handleExecute(value) }
                }
                EXECUTE_FRAGMENT -> thread(ActionExecutors.EXECUTE_FRAGMENT.executor, queuer, value) {
                    P8eMDC.set(value, clear = true)
                    transaction { envelopeGrpc.handleExecuteFragment(value) }
                }
                HEARTBEAT -> thread(ActionExecutors.HEARTBEAT.executor, event = value) {
                    val publicKey = publicKeyProto.signingPublicKey.toPublicKey()
                    P8eMDC.set(publicKey, clear = true)

                    try {
                        transaction { AffiliateConnectionRecord.heartbeat(publicKey, value.classname) }
                        queuer.queue(value)
                    } catch (t: Throwable) {
                        queuer.streamObserver.onError(t.statusRuntimeException())
                        disconnect(ExceptionState(t))
                    }
                }
                else -> queuer.error(IllegalStateException("Unknown action received by server ${value.action.name}").statusRuntimeException())
            }
        }
    }

    override fun onError(t: Throwable) {
        disconnect(ExceptionState(t))
    }

    override fun onCompleted() {
        disconnect(CompleteState)
    }

    private fun disconnect(state: EndState) {
        queuer.close(state)
        connectedKey.set(PK.PublicKey.getDefaultInstance())
        if (queuerKey != null && queuers[queuerKey!!] == queuer) {
            when (state) {
                is ExceptionState ->
                    logger().debug("GRPC Disconnected: Public Key ${queuerKey!!.publicKey.toHex()} Class ${queuerKey!!.classname}", state.t)
                is CompleteState ->
                    logger().debug("GRPC Disconnected: Public Key ${queuerKey!!.publicKey.toHex()} Class ${queuerKey!!.classname}")
                is NullState -> throw IllegalStateException("Invalid end state of null state.")
            } as Unit

            queuers.remove(queuerKey!!)
            transaction {
                AffiliateConnectionRecord.disconnect(
                    affiliateService.getSigningKeyPair(queuerKey!!.publicKey).public,
                    queuerKey!!.classname
                )
            }
        }
    }

    private fun connect(value: EnvelopeEvent): QueueingStreamObserverSender<EnvelopeEvent> {
        val publicKey = if(value.publicKey.hasEncryptionPublicKey()) value.publicKey.encryptionPublicKey.toPublicKey() else value.publicKey.signingPublicKey.toPublicKey()

        val streamObserver = timed("affiliate_connect") {
            transaction {
                // Verify that this is a known affiliate
                val affiliateRecord = affiliateService.get(publicKey)
                    ?: throw AffiliateConnectionException("Unable to find affiliate with requested public key: [${publicKey.toHex()}]")

                // Lock and mark connected or blow up
                val affiliateConnection = AffiliateConnectionRecord.findOrCreateForUpdate(
                        affiliateRecord.publicKey.value.toJavaPublicKey(),
                        value.classname
                )

                if (affiliateConnection.connectionStatus == CONNECTED) {
                    throw AffiliateConnectionException("Unable to listen for the same public key [${affiliateConnection.publicKey}] and classname [${affiliateConnection.classname}] combo more than once.")
                }

                affiliateConnection.connectionStatus = CONNECTED
                affiliateConnection.lastHeartbeat = OffsetDateTime.now()
            }

            logger().debug("GRPC Connected: [affiliate = ${queuerKey!!.publicKey.toHex()}, classname = ${queuerKey!!.classname}, action = ${value.action}, ip = ${clientIp()}]")

            connectedKey.set(publicKey.toPublicKeyProto())
            queuers.computeIfAbsent(queuerKey!!) {
                queuer
            }
        }

        return streamObserver
    }

    private fun thread(
        executor: ExecutorService,
        queuer: QueueingStreamObserverSender<EnvelopeEvent>? = null,
        event: EnvelopeEvent,
        fn: () -> Unit
    ): Future<*> =
        executor.submit {
            try {
                fn()
            } catch (t: Throwable) {
                if (queuer != null) {
                    EnvelopeEvent.newBuilder()
                        .setEvent(EventType.ENVELOPE_ERROR)
                        .setPublicKey(
                            PK.SigningAndEncryptionPublicKeys.newBuilder()
                                .setSigningPublicKey(queuerKey!!.publicKey.toPublicKeyProto())
                                .setEncryptionPublicKey(queuerKey!!.publicKey.toPublicKeyProto())
                        )
                        .setClassname(queuerKey!!.classname)
                        .setError(event.envelope.error(t.toMessageWithStackTrace(), EnvelopeError.Type.NO_ERROR_TYPE))
                        .build()
                        .let(queuer::queue)
                } else {
                    logger().error("", t)
                }
            }
        }

    companion object {
        private fun newExecutor(size: Int = 8, namingPattern: String) = ThreadPoolFactory.newFixedThreadPool(size, namingPattern)
    }

    enum class ActionExecutors(
        private val action: Action,
        val executor: ExecutorService
    ) {
        ACK(Action.ACK, newExecutor(4, "envelope-ack-%d")),
        HEARTBEAT(Action.HEARTBEAT, newExecutor(namingPattern = "envelope-heartbeat-%d")),
        EXECUTE(Action.EXECUTE, newExecutor(16, "envelope-execute-%d")),
        EXECUTE_FRAGMENT(Action.EXECUTE_FRAGMENT, newExecutor(namingPattern = "envelope-execute-fragment-%d"));
    }
}
