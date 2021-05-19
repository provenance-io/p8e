package io.provenance.engine.event

import io.p8e.proto.ContractScope.Envelope.Status.*
import io.p8e.proto.ContractScope.EnvelopeState
import io.p8e.proto.Envelope.EnvelopeUuidWithError
import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toProtoUuidProv
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.engine.domain.EventStatus
import io.provenance.engine.domain.toUuid
import io.provenance.engine.extension.addExpiration
import io.provenance.engine.grpc.v1.toEvent
import io.provenance.engine.service.ChaincodeInvokeService
import io.provenance.engine.service.EventService
import io.provenance.engine.service.MailboxService
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.state.EnvelopeStateEngine
import io.provenance.p8e.shared.util.P8eMDC
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.Callable

/**
 * Reaper for sending envelopes that have been signed by recitals but not yet chaincode executed.
 */
@Component
class ChaincodeHandler(
    private val chaincodeInvokeService: ChaincodeInvokeService,
    private val eventService: EventService,
    private val mailboxService: MailboxService,
    private val envelopeStateEngine: EnvelopeStateEngine
) {
    private val log = logger()

    init {
        eventService.registerCallback(Event.ENVELOPE_CHAINCODE, this::handleChaincode)
    }

    private class ChaincodeCallable(
        private val uuid: UUID,
        private val chaincodeInvokeService: ChaincodeInvokeService,
        private val eventService: EventService,
        private val mailboxService: MailboxService,
        private val envelopeStateEngine: EnvelopeStateEngine
    ) : Callable<EnvelopeState> {

        private val log = logger()

        override fun call(): EnvelopeState {
            var envelope = transaction {
                EnvelopeRecord.findForUpdate(uuid)!!
                    .also { P8eMDC.set(it, clear = true) }
            }

            log.info("Handling chaincode")

            // Cannot process envelopes that aren't in a SIGNED status.
            if (envelope.status != SIGNED) {
                log.warn("Envelope status of ${envelope.status} cannot be sent to the blockchain.")
                return envelope.data
            } else {
                log.info("Envelope queuing for memorialization")
            }

            // Expired
            if (envelope.isExpired()) {
                log.info("Envelope uuid: ${envelope.uuid} is expired")
                transaction {
                    EnvelopeRecord.findForUpdate(uuid)!!.also { env ->
                        if (env.isExpired()) {
                            env.data.also {
                                envelopeStateEngine.onHandleError(env)
                                val error = env.addExpiration()
                                val envWithError = EnvelopeUuidWithError.newBuilder()
                                        .setError(error)
                                        .setEnvelopeUuid(env.uuid.value.toProtoUuidProv())
                                        .build()
                                eventService.submitEvent(envWithError.toEvent(Event.ENVELOPE_ERROR), env.uuid.value)

                                mailboxService.error(env.scope.publicKey.toJavaPublicKey(), it.input, error)
                            }
                        }
                    }
                }.also {
                    envelope = it
                }

                return envelope.data
            }

            try {
                val result = chaincodeInvokeService.offer(envelope.data.result).get()

                // Grab the timestamp now so that the logs appear linear for research... There is a case where an envelope
                // completes but has not chaincode_time or chaincodeTransaction data. This will happen if the system restarts
                // before the lock on the envelope can be acquired below.
                val now = OffsetDateTime.now()

                // store the result in the db.
                transaction {
                    EnvelopeRecord.findByGroupAndExecutionUuid(envelope.groupUuid, envelope.executionUuid)
                        .apply {
                            forEach {
                                P8eMDC.set(it, clear = true)
                                it.run {
                                    // It's possible that the index acquires the lock on the envelope before we add the chaincode time.
                                    if (status == SIGNED || status == OUTBOX) {
                                        envelopeStateEngine.onHandleChaincode(this, now)
                                    }
                                    chaincodeTransaction = result
                                }
                            }
                        }.firstOrNull { it.uuid.value == uuid }!!
                }.also {
                    envelope = it
                }
            } catch (e: Exception) {
                log.warn("Unable to execute chaincode contract", e)
            }

            log.info("Completed chaincode envelope")
            return envelope.data
        }
    }

    fun handleChaincode(event: P8eEvent): EventStatus {
        if (event.event != Event.ENVELOPE_CHAINCODE) {
            throw IllegalStateException("Received event type ${event.event.name} in chaincode handler.")
        }
        val envelopeUuid = event.toUuid()

        return try {
            ChaincodeCallable(
                envelopeUuid,
                chaincodeInvokeService,
                eventService,
                mailboxService,
                envelopeStateEngine
            ).call()

            EventStatus.COMPLETE
        } catch (t: Throwable) {
            log.error("Error handling chaincode for envelope $envelopeUuid", t)
            EventStatus.ERROR
        }
    }
}
