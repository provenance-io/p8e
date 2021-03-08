package io.provenance.pbc.esc

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.provenance.pbc.ess.proto.EventStreamGrpc
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class EventStreamApiKey(val value: String)

data class EventStreamProperties(
    val uri: URI,
    val apiKey: EventStreamApiKey
)

fun EventStreamProperties.consumer(): String = StreamClientParams(
    uri = this.uri,
    apiKey = this.apiKey.value
).consumer

/**
 * A long lived stream that forwards onNext invocations to onNextHandler. The onNext
 * method catches exceptions and calls onError to guarantee no more events come through onNext until
 * we're able to fully shutdown this StreamObserver, because of this the onNextHandler must throw an Exception
 * in cases where it cannot successfully complete.
 *
 * A CountDownLatch is used to determine when this StreamObserver is in a terminal state.
 */
class EventStreamResponseObserver<T>(private val onNextHandler: (T) -> Unit) : StreamObserver<T> {
    val finishLatch: CountDownLatch = CountDownLatch(1)
    private var error: Throwable? = null

    fun error(): Throwable? = error

    override fun onNext(value: T) {
        try {
            onNextHandler(value)
        } catch (t: Throwable) {
            this.onError(t)
        }
    }

    override fun onCompleted() {
        finishLatch.countDown()
    }

    override fun onError(t: Throwable?) {
        error = t
        finishLatch.countDown()
    }
}

/**
 * Simple abstraction around The EventStreamGrpc interface. This class does not include a StreamObserver in case
 * a custom implementation is required. EventStreamResponseObserver is provided for convenience.
 *
 * An example usage of this class is provided:
 *
 * @Scheduled(fixedDelay = 30_000)
 * fun consumeEventStream() {
 *     // channel is instantiated elsewhere
 *     val channel: EventStreamChannel
 *     val height = 0L
 *     val request = EventProtos.EventStreamReq.newBuilder()
 *         .addAllEventTypes(listOf("scope_created"))
 *         .setStartHeight(height)
 *         .setConsumer(channel.eventStreamProperties.consumer())
 *         .build()
 *     val responseObserver = EventStreamResponseObserver<EventProtos.EventBatch> {
 *         // do something with batch
 *     }
 *
 *     log.info("Starting event stream at height $height")
 *
 *     channel.eventAsyncClient.streamEvents(request, responseObserver)
 *
 *     while (true) {
 *         val isComplete = responseObserver.finishLatch.await(60, TimeUnit.SECONDS)
 *
 *         when (Pair(isComplete, responseObserver.error())) {
 *             Pair(false, null) -> { log.info("Event stream active ping") }
 *             Pair(true, null) -> { log.warn("Received completed"); return }
 *             else -> { throw responseObserver.error()!! }
 *         } as Unit
 *     }
 * }
 */
class EventStreamChannel(val eventStreamProperties: EventStreamProperties) {

    val channel: ManagedChannel
    val eventAsyncClient: EventStreamGrpc.EventStreamStub

    init {
        channel = ManagedChannelBuilder.forAddress(eventStreamProperties.uri.host, eventStreamProperties.uri.port)
            .also {
                if (eventStreamProperties.uri.scheme == "grpcs") {
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

        eventAsyncClient = EventStreamGrpc.newStub(channel)
            .withCallCredentials(ApiKeyCallCredentials(eventStreamProperties.apiKey))
    }
}
