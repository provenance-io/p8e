package io.provenance.engine.stream

import com.tinder.scarlet.Lifecycle
import com.tinder.scarlet.Scarlet
import com.tinder.scarlet.ShutdownReason
import com.tinder.scarlet.WebSocket
import com.tinder.scarlet.lifecycle.LifecycleRegistry
import io.p8e.crypto.Hash
import io.p8e.engine.threadedMap
import io.p8e.util.ThreadPoolFactory
import io.p8e.util.base64decode
import io.p8e.util.timed
import io.p8e.util.toHexString
import io.provenance.engine.batch.removeShutdownHook
import io.provenance.engine.batch.shutdownHook
import io.provenance.engine.service.TransactionQueryService
import io.provenance.engine.stream.domain.*
import io.provenance.p8e.shared.extension.logger
import io.reactivex.disposables.Disposable
import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class EventStreamStaleException(message: String) : Throwable(message)

@Component
class EventStreamFactory(
    private val transactionQueryService: TransactionQueryService,
    private val eventStreamBuilder: Scarlet.Builder
) {
    private val log = logger()

    fun getStream(eventTypes: List<String>, startHeight: Long, observer: EventStreamResponseObserver<EventBatch>): EventStream {
        val lifecycle = LifecycleRegistry(0L)

        return EventStream(
            eventTypes,
            startHeight,
            observer,
            lifecycle,
            eventStreamBuilder.lifecycle(lifecycle).build().create<EventStreamService>(),
            transactionQueryService
        )
    }

    class EventStream(
        val eventTypes: List<String>,
        val startHeight: Long,
        private val observer: EventStreamResponseObserver<EventBatch>,
        private val lifecycle: LifecycleRegistry,
        private val eventStreamService: EventStreamService,
        private val transactionQueryService: TransactionQueryService
    ) {
        companion object {
            private val HISTORY_BATCH_SIZE = 10
            private val executor = ThreadPoolFactory.newFixedThreadPool(HISTORY_BATCH_SIZE, "event-stream-%d")
        }

        private val log = logger()

        private var subscription: Disposable? = null

        private var shuttingDown = CountDownLatch(1)
        private var shutdownHook: Thread? = null

        private var lastBlockSeen = AtomicLong(-1)
        private val eventMonitor = thread(start = false, isDaemon = true) {
            var lastBlockMonitored = lastBlockSeen.get()
            while (!shuttingDown.await(30, TimeUnit.SECONDS)) {
                val lastBlockSeen = lastBlockSeen.get()
                log.debug("Checking for event stream liveliness [lastBlockSeen: $lastBlockSeen vs. lastBlockMonitored: $lastBlockMonitored]")
                if (lastBlockSeen <= lastBlockMonitored) {
                    handleError(EventStreamStaleException("EventStream has not received a block in 30 seconds [lastBlockSeen: $lastBlockSeen vs. lastBlockMonitored: $lastBlockMonitored]"))
                    break
                }
                lastBlockMonitored = lastBlockSeen
            }
            log.info("Exiting event monitor thread")
        }

        fun streamEvents() {
            // todo: concurrency - need to limit how many times this function is called??? Used to limit based on consumer id... probably need to use redis to do this... and ensure cleaned up when shutting down if do

            try {
                // start event loop to start listening for events
                startEventLoop()
                shutdownHook = shutdownHook { shutdown(false) } // ensure we close socket gracefully when shutting down

                timed("EventStream:streamHistory") {
                    streamHistory()
                }

                eventMonitor.start()
            } catch (t: Throwable) {
                log.error("Error starting up EventStream: ${t.message}")
                handleError(t)
            }
        }

        private fun streamHistory() {
            if (startHeight <= 0) {
                // client only wants live events
                return
            }

            // get latest block height
            val lastBlockHeight = transactionQueryService.abciInfo().also {
                info -> log.info("EventStream lastBlockHeight: ${info.lastBlockHeight}")
            }.lastBlockHeight

            // Requested start height is in the future
            if (startHeight > lastBlockHeight) {
                return
            }

            // query block heights in batches (concurrent batches)
            var height = startHeight
            val capacity = HISTORY_BATCH_SIZE
            val capacityRange = (0 until capacity).toList()
            val chunkSize = 20 // Tendermint limit on how many block metas can be queried in one call.
            var numHistoricalEvents = 0
            do {
                val batches = capacityRange.threadedMap(executor) { i ->
                    val beg = height + i * chunkSize
                    if (beg > lastBlockHeight) {
                        null
                    } else {
                        var end = beg + chunkSize - 1
                        if (end > lastBlockHeight) {
                            end = lastBlockHeight
                        }
                        queryBatchRange(beg, end)
                    }
                }.filterNotNull()
                .flatten()

                if (batches.isNotEmpty()) {
                    numHistoricalEvents += batches.fold(0) { acc, batch -> acc + batch.events.size }

                    batches.sortedBy { it.height }
                        .forEach {
                            handleEventBatch(it)
                        }
                }

                height += capacity * chunkSize
            } while (height <= lastBlockHeight)

            log.info("Streamed $numHistoricalEvents historical events")
        }

        private fun startEventLoop() {
            // subscribe to block events tm.event='NewBlock'
            log.info("opening EventStream websocket")
            lifecycle.onNext(Lifecycle.State.Started)
            subscription = eventStreamService.observeWebSocketEvent()
                .filter {
                    if (it is WebSocket.Event.OnConnectionFailed) {
                        handleError(it.throwable)
                    }

                    it is WebSocket.Event.OnConnectionOpened<*>
                }
                .switchMap {
                    log.info("initializing subscription for tm.event='NewBlock'")
                    eventStreamService.subscribe(Subscribe("tm.event='NewBlock'"))
                    eventStreamService.streamEvents()
                }
                .filter { !it.result!!.query.isNullOrBlank() && it.result.data.value.block.header.height >= startHeight }
                .map { event -> event.result!! }
                .subscribe(
                    { handleEvent(it) },
                    { handleError(it) }
                )
        }

        private fun handleEvent(event: Result) {
            val blockHeight = event.data.value.block.header.height

            lastBlockSeen.set(blockHeight)

            queryEvents(blockHeight)
                .takeIf { it.isNotEmpty() }
                ?.also {
                    log.info("got batch of ${it.count()} events")
                    handleEventBatch(
                        EventBatch(blockHeight, it)
                    )
                }
        }

        private fun handleEventBatch(event: EventBatch) {
            observer.onNext(event)
        }

        private fun handleError(t: Throwable) {
            log.error("EventStream error ${t.message}")
            observer.onError(t)
            shutdown()
        }

        fun shutdown(removeShutdownHook: Boolean = true) {
            log.info("Cleaning up EventStream Websocket")
            shuttingDown.countDown()
            lifecycle.onNext(Lifecycle.State.Stopped.WithReason(ShutdownReason.GRACEFUL))
            subscription
                ?.takeIf { !it.isDisposed }
                ?.dispose()
            observer.onCompleted()
            shutdownHook?.takeIf { removeShutdownHook }?.also { removeShutdownHook(it) }
        }

        fun queryBatchRange(minHeight: Long, maxHeight: Long): List<EventBatch>? {
            if (minHeight > maxHeight) {
                return null
            }

            return transactionQueryService.blocksWithTransactions(minHeight, maxHeight)
                .takeIf { it.isNotEmpty() }
                ?.map { height ->
                    EventBatch(
                        height,
                        queryEvents(height)
                    )
                }?.filter { it.events.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
        }

        fun queryEvents(height: Long): List<StreamEvent> {
            val block = transactionQueryService.block(height)
            if (block.block.data.txs == null || block.block.data.txs.isEmpty()) { // empty block
                return listOf()
            }

            val results = transactionQueryService.blockResults(height)

            return results.txsResults
                ?.flatMapIndexed { index, tx ->
                    val txHash = block.block.data.txs[index].hash()
                    tx.events
                        .filter { it.shouldStream() }
                        .map { event ->
                            StreamEvent(
                                height = height,
                                eventType = event.type,
                                resultIndex = index,
                                txHash = txHash,
                                attributes = event.attributes
                            )
                        }
                } ?: listOf()
        }

        private fun Event.shouldStream(): Boolean = eventTypes.contains(type) // check for simple event type match first
            || eventTypes.isEmpty() // no filtering requested
            // Check for "event_type:attribute_key" matches.
            || eventTypes.firstOrNull {
                it.contains(':')
                && it.split(':').let { elements ->
                    elements.size == 2
                    && elements[0] == type // event type match
                    && attributes.firstOrNull { attribute -> // at least one attribute match
                        attribute.key == elements[1]
                    } != null
                }
            } != null

        fun String.hash(): String = Hash.sha256(base64decode()).toHexString()
    }
}
