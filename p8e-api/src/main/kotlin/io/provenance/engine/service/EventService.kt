package io.provenance.engine.service

import com.google.protobuf.util.JsonFormat
import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.util.ThreadPoolFactory
import io.p8e.util.orThrow
import io.p8e.util.timed
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.domain.EventRecord
import io.provenance.engine.domain.EventStatus
import io.provenance.engine.domain.EventTable
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.*
import kotlin.concurrent.thread

const val RETRY_LIMIT = 1_000

@Component
class EventService() {
    private val log = logger()

    init {
        listen()
    }

    companion object {
        val SKIPPABLE_EVENTS = listOf(Event.UNRECOGNIZED, Event.SCOPE_INDEX_FRAGMENT)
        val CONNECTED_CLIENT_EVENTS = listOf(Event.ENVELOPE_REQUEST, Event.ENVELOPE_RESPONSE, Event.ENVELOPE_ERROR)
        val UNCONNECTED_CLIENT_EVENTS = Event.values().toList().minus(CONNECTED_CLIENT_EVENTS).minus(SKIPPABLE_EVENTS)
    }

    fun registerCallback(event: Event, callback: EventHandler) {
        log.info("Registering event callback {}", event)

        NotificationHandler.callbacks.computeIfAbsent(event) {
            mutableListOf()
        }.add(callback)
    }

    fun submitEvent(event: P8eEvent, envelopeUuid: UUID, status: EventStatus = EventStatus.CREATED, created: OffsetDateTime = OffsetDateTime.now()): EventRecord =
        EventRecord.insertOrUpdate(event, envelopeUuid, status, created, created).also(::submitToChannel)

    fun completeInProgressEvent(envelopeUuid: UUID) = EventRecord.findByEnvelopeUuidForUpdate(envelopeUuid)?.let {
        it.status = EventStatus.COMPLETE
    }

    private fun submitToChannel(record: EventRecord) {
        if (!SKIPPABLE_EVENTS.contains(record.event)) {
            record.putAfterCommit()
        }
    }

    private fun EventRecord.retry() {
        log.debug("Retrying Event:: $eventUuid")

        status = EventStatus.CREATED
        updated = OffsetDateTime.now()

        submitToChannel(this)
    }

    @Scheduled(initialDelay = 33000, fixedDelay = 61000)
    fun retryEvents() {
        log.info("Checking for stale events...")

        // retry all events that don't require client to be connected and are > 5 minutes old
        pollChunked {
            EventRecord.find {(
                EventTable.event inList UNCONNECTED_CLIENT_EVENTS
                        and (EventTable.status neq EventStatus.COMPLETE)
                        and (EventTable.updated less OffsetDateTime.now().withOffsetSameInstant(ZoneOffset.UTC)
                    .minusMinutes(5))
            )}
        }

        // retry events that require a connected client for clients who are connected and are > 30s old
        pollChunked {
            EventRecord.findForConnectedClients { (
                EventTable.event inList CONNECTED_CLIENT_EVENTS
                        and (EventTable.status neq EventStatus.COMPLETE)
                        and (EventTable.updated less OffsetDateTime.now().withOffsetSameInstant(ZoneOffset.UTC)
                    .minusSeconds(30))
            )}
        }
    }

    private fun pollChunked(query: () -> SizedIterable<EventRecord>) {
        do {
            val count = transaction {
                query()
                    .orderBy(EventTable.updated to SortOrder.ASC)
                    .limit(RETRY_LIMIT)
                    .forUpdate()
                    .also { if (it.count() > 0) { log.info("Queueing ${it.count()} events for retry") } }
                    .map { it.retry() }
                    .count()
            }

            // allows for ~ 2K retry events per second
            if (count == RETRY_LIMIT) {
                Thread.sleep(500)
            }
        } while (count == RETRY_LIMIT)
    }

    private fun listen() {
        Event.values()
            .toMutableSet()
            .minus(SKIPPABLE_EVENTS)
            .forEach { event ->
                listenerWithRetry(event)
            }
    }

    // Watch dog. Keep the listeners running.
    private fun listenerWithRetry(event: Event) {
        val retryDelay = 120000L

        listener(event)
            .thenRunAsync {
                log.info("exiting listener for ${event.name} normally")
                Thread.sleep(retryDelay)
                listenerWithRetry(event)
            }
            .exceptionally {
                log.info("exiting listener for ${event.name} exceptionally")
                log.warn("Listener shutdown", it)
                Thread.sleep(retryDelay)
                listenerWithRetry(event)
                null
            }
    }

    private fun listener(event: Event): CompletableFuture<Void?> {
        val completableFuture = CompletableFuture<Void?>()

        thread(isDaemon = true) {
            try {
                log.info("Starting listener for event: ${event.name}")

                NotificationHandler(event).consumeChannel()

                log.info("Exiting listener for event: ${event.name}")

                completableFuture.complete(null)
            } catch (t: Throwable) {
                log.error("Received exception in event listener: ", t)
                completableFuture.completeExceptionally(t)
            }
        }

        return completableFuture
    }
}

class NotificationHandler(private val event: Event) {

    private val log = logger()

    fun consumeChannel() {
        val channel = NotificationHandler.channels.computeIfAbsent(event) {
            LinkedBlockingQueue<EventRecord>(10_000)
        }

        while (true) {
            val record = channel.take()

            notification(record)
        }
    }

    fun notification(tableEvent: EventRecord) {
        thread(pool = computeExecutor(tableEvent.event)) {
            try {
                when (tableEvent.status) {
                    EventStatus.CREATED -> {
                        log.info("Received CREATED event: [${JsonFormat.printer().print(tableEvent.payload)}")

                        handleEvent(tableEvent.eventUuid.value, tableEvent.payload)
                    }
                    else -> {
                        // NOOP
                        log.debug("Skipped ${tableEvent.status} event: [${JsonFormat.printer().print(tableEvent.payload)}")
                    }
                }
            } catch (e: Exception) {
                log.error("Unexpected exception processing table event with payload:{}", tableEvent, e)
            }
        }
    }

    private fun handleEvent(uuid: UUID, event: P8eEvent) {
        timed("handle_event_${event.event.name}") {
            callbacks[event.event]
                    ?.forEach { callback ->
                        when (callback.invoke(event)) {
                            EventStatus.COMPLETE -> updateEvent(uuid, event.event, EventStatus.COMPLETE)
                            EventStatus.ERROR -> updateEvent(uuid, event.event, EventStatus.ERROR)
                            EventStatus.CREATED, null -> Unit
                        } as Any?
                    } ?: log.warn("Unable to find callback for event: ${event.event}")
        }
    }

    private fun updateEvent(uuid: UUID, event: Event, eventStatus: EventStatus): Unit = transaction {
        EventRecord.findForUpdate(uuid)
            ?.takeIf { it.event == event }
            ?.also {
                it.status = eventStatus
                it.updated = OffsetDateTime.now()
            }
    }

    companion object {
        val callbacks = ConcurrentHashMap<Event, MutableList<EventHandler>>()
        val channels = ConcurrentHashMap<Event, LinkedBlockingQueue<EventRecord>>()
        private val executors = ConcurrentHashMap<P8eEvent.Event, ScheduledExecutorService>().apply {
            put(Event.ENVELOPE_CHAINCODE, threadPool(Event.ENVELOPE_CHAINCODE, 50))
            put(Event.SCOPE_INDEX, threadPool(Event.SCOPE_INDEX, 50))
        }
        private fun computeExecutor(event: Event): ScheduledExecutorService =
            executors.computeIfAbsent(event) {
                threadPool(it, 8)
            }

        private fun threadPool(event: Event, size: Int) =
            ThreadPoolFactory.newScheduledThreadPool(size, "psql-event-${event.name.toLowerCase()}-%d")

        private fun thread(
            delay: Long? = null,
            delayTimeUnit: TimeUnit? = null,
            pool: ScheduledExecutorService,
            fn: () -> Unit
        ): Future<*> {
            return thread(start = false) {
                try {
                    fn()
                } catch(t: Throwable) {
                    logger().error("", t)
                }
            }.let { thread ->
                if (delay != null && delayTimeUnit != null) {
                    pool.schedule(thread, delay, delayTimeUnit)
                } else {
                    pool.submit(thread)
                }
            }
        }
    }
}

typealias EventHandler = (P8eEvent) -> EventStatus?

private fun Event.channel() = NotificationHandler.channels[this].orThrow { IllegalStateException("Event ${this.name} has no channel") }
private fun EventRecord.putAfterCommit() = TransactionManager.current().registerInterceptor(object : StatementInterceptor {
    override fun afterCommit() {
        event.channel().put(this@putAfterCommit)
    }
})
