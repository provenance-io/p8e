package io.provenance.engine.stream

import com.tinder.scarlet.*
import com.tinder.scarlet.lifecycle.LifecycleRegistry
import com.tinder.scarlet.messageadapter.moshi.MoshiMessageAdapter
import com.tinder.scarlet.streamadapter.rxjava2.RxJava2StreamAdapterFactory
import com.tinder.scarlet.websocket.okhttp.newWebSocketFactory
import io.grpc.netty.NettyChannelBuilder
import io.p8e.crypto.Hash
import io.p8e.engine.threadedMap
import io.p8e.util.ThreadPoolFactory
import io.p8e.util.timed
import io.p8e.util.toHexString
import io.provenance.engine.batch.shutdownHook
import io.provenance.engine.config.EventStreamProperties
import io.provenance.engine.service.TransactionQueryService
import io.provenance.engine.stream.domain.*
import io.provenance.engine.stream.domain.Event
import io.provenance.p8e.shared.extension.logger
import io.reactivex.disposables.Disposable
import okhttp3.OkHttpClient
import org.springframework.stereotype.Component
import tendermint.abci.ABCIApplicationGrpc
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@Component
class EventStreamFactory(
    private val eventStreamProperties: EventStreamProperties,
    private val transactionQueryService: TransactionQueryService
) {
    private val log = logger()

    private val abciService = URI(eventStreamProperties.uri).let { uri ->
        log.info("uri is ${eventStreamProperties.uri}")
        val channel = NettyChannelBuilder.forAddress(uri.host, uri.port)
            // todo: set appropriate options for timeouts and what-not
            .also {
                if (uri.scheme == "grpcs") {
                    it.useTransportSecurity()
                } else {
                    it.usePlaintext()
                }
            }
            .maxInboundMessageSize(20 * 1024 * 1024) // ~ 20 MB
            .idleTimeout(5, TimeUnit.MINUTES)
            .keepAliveTime(60, TimeUnit.SECONDS) // ~ 12 pbc block cuts
            .keepAliveTimeout(20, TimeUnit.SECONDS)
            .build()

        ABCIApplicationGrpc.newBlockingStub(channel)
    }

    fun getStream(eventTypes: List<String>, startHeight: Long, observer: EventStreamResponseObserver<EventBatch>) = EventStream(URI(eventStreamProperties.websocketUri), eventTypes, startHeight, observer, transactionQueryService)

    class EventStream(
        val node: URI,
        val eventTypes: List<String>,
        val startHeight: Long,
        private val observer: EventStreamResponseObserver<EventBatch>,
        private val transactionQueryService: TransactionQueryService
    ) {
        companion object {
            private val HISTORY_BATCH_SIZE = 10
            private val executor = ThreadPoolFactory.newFixedThreadPool(HISTORY_BATCH_SIZE, "event-stream-%d")
        }

        private val log = logger()

        private val lifecycle = LifecycleRegistry(0L)
        private val scarletInstance = Scarlet.Builder()
            .lifecycle(lifecycle)
            .webSocketFactory(OkHttpClient.Builder()
                .readTimeout(Duration.ofSeconds(60)) // ~ 12 pbc block cuts
                .build()
                .newWebSocketFactory("${node.scheme}://${node.host}:${node.port}/websocket"))
            .addMessageAdapterFactory(MoshiMessageAdapter.Factory())
            .addStreamAdapterFactory(RxJava2StreamAdapterFactory())
            .build()
        private val eventStreamService = scarletInstance.create<EventStreamService>()

        private var subscription: Disposable? = null

        fun streamEvents() {
            // need to limit how many times this function is called??? Used to limit based on consumer id... probably need to use redis to do this... and ensure cleaned up when shutting down if do

            // start event loop to start buffering events
//            startEventLoop()

            timed("EventStream:streamHistory") {
                streamHistory()
            }

            // check tendermint node connection every 30s

            // listen for live events
        }

        private fun streamHistory() {
            if (startHeight <= 0) {
                // client only wants live events
                return
            }

            // get latest block height
            val lastBlockHeight = transactionQueryService.abciInfo().also {
                info -> log.info("lastBlockHeight: ${info.lastBlockHeight}")
            }.lastBlockHeight

            // Requested start height is in the future
            if (startHeight > lastBlockHeight) {
                return
            }

            // query block heights in batches (concurrent batches)
            var height = startHeight
            val capacity = HISTORY_BATCH_SIZE
            val chunkSize = 20 // Tendermint limit on how many block metas can be queried in one call.
            do {
                val batches = (0 until capacity).toList().threadedMap(executor) { i ->
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
                    batches.sortedBy { it.height }
                        .forEach {
                            handleEvent(it)
                        }
                }

                height += capacity * chunkSize
            } while (height <= lastBlockHeight)
        }

        private fun startEventLoop() {
            // subscribe to block events tm.event='NewBlock' (handle fail)
            lifecycle.onNext(Lifecycle.State.Started)
            subscription = eventStreamService.observeWebSocketEvent()
                .filter { it is WebSocket.Event.OnConnectionOpened<*> }
                .switchMap {
                    eventStreamService.subscribe(Subscribe("tm.event='NewBlock'"))
                    eventStreamService.streamEvents()
                }
                .filter { !it.result.query.isNullOrBlank() && it.result.data.value.block.header.height >= startHeight }
                .map { event -> event.result }
                .subscribe({
//                    handleEvent(it) // todo: fetch event details
                }) { t -> handleError(t) }

            // query height to get transactions (handle fail) (queryBlockEvents)

            // pass events to buffer w/ height
        }

        private fun handleEvent(event: EventBatch) {
            log.info("got event! $event")
            observer.onNext(event)
        }

        private fun handleError(t: Throwable) {
            log.error("EventStream error ${t.message}")
            observer.onError(t)
        }

        fun shutdown() {
            log.info("Cleaning up EventStream Websocket")
            eventStreamService.unsubscribe()
            lifecycle.onNext(Lifecycle.State.Stopped.WithReason(ShutdownReason.GRACEFUL))
            subscription
                ?.takeIf { !it.isDisposed }
                ?.dispose()
            observer.onCompleted()
        }

        fun queryBatchRange(minHeight: Long, maxHeight: Long): List<EventBatch>? {
            if (minHeight > maxHeight) {
                return null
            }

            return transactionQueryService.blocksWithTransactions(minHeight, maxHeight)
                .takeIf { it.count() > 0 }
                ?.map { height ->
                    EventBatch(
                        height,
                        queryEvents(height)
                    )
                }
        }

        fun queryEvents(height: Long): List<StreamEvent> {
            val block = transactionQueryService.block(height)
            val results = transactionQueryService.blockResults(height)

            return results.txsResults
                .flatMapIndexed { index, tx ->
                    val txHash = block.block.data.txs[index].hash()
                    tx.events
                        .filter { it.shouldStream() }
                        .map { event ->
                            StreamEvent(
                                height = results.height,
                                eventType = event.type,
                                resultIndex = index,
                                txHash = txHash,
                                attributes = event.attributes
                            )
                        }
                }
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

        fun ByteArray.hash(): String = Hash.sha256(this).toHexString()
    }
}
