package io.provenance.engine.batch

import io.p8e.proto.ContractScope.EnvelopeState
import io.p8e.proto.Envelope.EnvelopeUuidWithError
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.util.toJavaPublicKey
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toProtoUuidProv
import io.provenance.engine.config.ReaperFragmentProperties
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.engine.extension.addExpiration
import io.provenance.engine.grpc.v1.toEvent
import io.provenance.engine.service.EventService
import io.provenance.engine.service.MailboxService
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/**
 * Reaper for sending invoker envelopes that have expired to audience list.
 */
@Component
class ExpirationReaper(
    private val mailboxService: MailboxService,
    reaperFragmentProperties: ReaperFragmentProperties,
    private val eventService: EventService
) {

    private val log = logger()
    private val query = """
        SELECT uuid FROM envelope e
        WHERE e.error_time IS NULL
        AND e.chaincode_time IS NULL
        AND e.is_invoker IS NOT NULL
        AND expiration_time <= now()
        LIMIT 10;
    """.trimIndent()
    private val exec: ExecutorService = reaperFragmentProperties.toThreadPool()

    private class ExpirationCallable(
        private val uuid: UUID,
        private val mailboxService: MailboxService,
        private val eventService: EventService
    ) : Callable<EnvelopeState> {

        private val log = logger()

        override fun call(): EnvelopeState = transaction {
            log.info("Processing expiration envelope:{}", uuid)

            val record = EnvelopeRecord.findForUpdate(uuid)!!

            if (record.data.hasErrorTime())
                return@transaction record.data

            val error = record.addExpiration()
            val envWithError = EnvelopeUuidWithError.newBuilder()
                .setError(error)
                .setEnvelopeUuid(record.uuid.value.toProtoUuidProv())
                .build()
            eventService.submitEvent(envWithError.toEvent(Event.ENVELOPE_ERROR), record.uuid.value)
            mailboxService.error(record.scope.publicKey.toJavaPublicKey(), record.data.result, error)

            record.data
        }.also { log.info("Completed processing expiration envelope:{}", uuid) }
    }

    @Scheduled(initialDelayString = "\${reaper.expiration.delay}", fixedDelayString = "\${reaper.expiration.interval}")
    fun poll() {
        log.debug("Polling expiration reaper")

        transaction { EnvelopeRecord.findUuids(query) }
            .takeIf { it.isNotEmpty() }
            ?.also { log.info("Expiration reaper polled envelope count:{} for cancellation", it.size) }
            ?.map { uuid -> ExpirationCallable(uuid, mailboxService, eventService) }
            ?.let { exec.invokeAll(it) }
            ?.run{ awaitFutures(ExpirationCallable::class) }
    }
}
