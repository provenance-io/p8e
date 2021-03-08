package io.provenance.engine.event

import io.p8e.proto.ContractScope.EnvelopeState
import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.util.toJavaPublicKey
import io.provenance.engine.config.ReaperFragmentProperties
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.engine.domain.EventStatus
import io.provenance.engine.domain.toUuid
import io.provenance.engine.extension.addExpiration
import io.provenance.engine.grpc.v1.toEvent
import io.provenance.engine.service.EventService
import io.provenance.engine.service.MailboxService
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.state.EnvelopeStateEngine
import io.provenance.p8e.shared.util.P8eMDC
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/**
 * Reaper for sending envelopes that have been executed by invoker but not yet fragmented via mailbox.
 */
@Component
class FragmentHandler(
    private val eventService: EventService,
    private val mailboxService: MailboxService,
    private val envelopeStateEngine: EnvelopeStateEngine,
    reaperFragmentProperties: ReaperFragmentProperties
) {

    private val log = logger()
    private val query = """
        SELECT uuid FROM envelope e
        WHERE e.fragment_time IS NULL
        AND e.error_time IS NULL
        AND e.is_invoker IS NOT NULL
        LIMIT 10;
    """.trimIndent()
    private val exec: ExecutorService = reaperFragmentProperties.toThreadPool()

    init {
        eventService.registerCallback(Event.ENVELOPE_FRAGMENT, this::handleFragment)
    }

    private class FragmentCallable(
        private val envelopeUuid: UUID,
        private val mailboxService: MailboxService,
        private val envelopeStateEngine: EnvelopeStateEngine,
        private val eventService: EventService
    ) : Callable<EnvelopeState> {

        private val log = logger()

        override fun call(): EnvelopeState = transaction {
            val record = EnvelopeRecord.findForUpdate(envelopeUuid)!!
                .also { P8eMDC.set(it, clear = true) }

            log.info("Processing fragment")

            // Lock check is for mult-node support only
            if (record.data.hasFragmentTime())
                return@transaction record.data

            // Expire if past ttl of execution
            if (record.isExpired())
                // No need to send to audiences if not yet fragmented so they do not have it yet
                return@transaction record.addExpiration()
                    .also { eventService.submitEvent(it.toEvent(Event.ENVELOPE_ERROR), record.uuid.value) }
                    .run { record.data }

            mailboxService.fragment(record.scope.publicKey.toJavaPublicKey(), record.data.input)
            envelopeStateEngine.onHandleFragment(record)
            record.data
        }.also { log.info("Completed processing fragment envelope:{}", envelopeUuid) }
    }

    fun handleFragment(event: P8eEvent): EventStatus {
        if (event.event != Event.ENVELOPE_FRAGMENT) {
            throw IllegalStateException("Received table event type ${event.event} in fragment handler.")
        }

        val envelopeUuid = event.toUuid()

        return try {
            FragmentCallable(
                envelopeUuid,
                mailboxService,
                envelopeStateEngine,
                eventService
            ).let { exec.submit(it) }
                .get()
            EventStatus.COMPLETE
        } catch (t: Throwable) {
            log.error("Error handling fragment for envelope $envelopeUuid", t)
            EventStatus.ERROR
        }
    }
}
