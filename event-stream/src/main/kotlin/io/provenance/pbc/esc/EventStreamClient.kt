package io.provenance.pbc.esc

import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.StreamObserver
import io.provenance.pbc.ess.proto.EventProtos.ErrorStreamReq
import io.provenance.pbc.ess.proto.EventProtos.EventBatch
import io.provenance.pbc.ess.proto.EventProtos.EventStreamReq
import io.provenance.pbc.ess.proto.EventProtos.TxError
import io.provenance.pbc.ess.proto.EventStreamGrpc
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

fun <E> streamObserver(next: (E) -> Unit, error: (Throwable) -> Unit, completed: () -> Unit): StreamObserver<E> {
    return object : StreamObserver<E> {
        override fun onNext(value: E) = next(value)
        override fun onError(t: Throwable) = error(t)
        override fun onCompleted() = completed()
    }
}

class ApiKeyCallCredentials(apiKey: EventStreamApiKey) : CallCredentials() {
    private val key = Metadata.Key.of("apikey", Metadata.ASCII_STRING_MARSHALLER)
    private val headers = Metadata().also {
        it.put(key, apiKey.value)
    }

    override fun applyRequestMetadata(requestInfo: RequestInfo, appExecutor: Executor, applier: MetadataApplier) {
        appExecutor.execute {
            applier.apply(headers)
        }
    }

    override fun thisUsesUnstableApi() {
        // No-op.
    }
}

private fun managedChannel(uri: URI): ManagedChannel =
    NettyChannelBuilder.forAddress(uri.host, uri.port)
        .maxInboundMessageSize(20 * 1024 * 1024)
        .idleTimeout(5, TimeUnit.MINUTES)
        .keepAliveTime(60, TimeUnit.SECONDS) // ~ 12 pbc block cuts
        .keepAliveTimeout(20, TimeUnit.SECONDS)
        .also {
            if (uri.scheme == "grpc") {
                it.usePlaintext()
            }
        }.build()

interface StreamClient<T> {
    fun runAsync(observer: StreamObserver<T>)
}

data class StreamClientParams(
        val uri: URI,
        val apiKey: String = "",
        val stopOnError: Boolean = true,
        val consumer: String = ""
)

class ErrorStreamClient(
        params: StreamClientParams,
        handle: (TxError) -> Unit,
        errorHandle: (Throwable) -> Unit
) : BaseStreamClient<TxError>(params, handle, errorHandle) {
    private val req = ErrorStreamReq.newBuilder().setConsumer(params.consumer).build()

    private val grpc = EventStreamGrpc
            .newStub(channel)
            .withCallCredentials(callbackCredentials)

    override fun runAsync(observer: StreamObserver<TxError>) {
        grpc.streamErrors(req, observer)
    }
}

class EventStreamClient(
        eventTypes: List<String>,
        startHeight: Long = 1,
        params: StreamClientParams,
        handle: (EventBatch) -> Unit,
        errorHandle: (Throwable) -> Unit
) : BaseStreamClient<EventBatch>(params, handle, errorHandle) {
    private val req = EventStreamReq.newBuilder()
            .addAllEventTypes(eventTypes)
            .setStartHeight(startHeight)
            .setConsumer(params.consumer)
            .build()

    private val grpc = EventStreamGrpc
            .newStub(channel)
            .withCallCredentials(callbackCredentials)

    override fun runAsync(observer: StreamObserver<EventBatch>) {
        grpc.streamEvents(req, observer)
    }
}

abstract class BaseStreamClient<T>(
        params: StreamClientParams,
        eventHandle: (T) -> Unit,
        errorHandle: (Throwable) -> Unit
) : StreamClient<T> {
    protected val channel = managedChannel(params.uri)
    protected val callbackCredentials = ApiKeyCallCredentials(EventStreamApiKey(params.apiKey))

    private val _completed = AtomicBoolean(false)

    private val isCompleted get() = _completed.get()

    private val observer = streamObserver(
            next = eventHandle,
            error = {
                try {
                    errorHandle(it)
                } finally { // Ensure we can stop when error handler throws an error
                    if (params.stopOnError) {
                        stop()
                    }
                }
            },
            completed = { _completed.set(true) }
    )

    private val lock = Object()

    private val log = LoggerFactory.getLogger(javaClass)


    // Run the stream until complete
    fun run() {
        log.info("run()")
        runAsync(observer)
        waitForComplete()
    }

    // Run the stream in a thread until complete
    fun start() {
        log.info("start()")
        thread {
            run()
        }
    }

    // Wait for completion status
    fun waitForComplete() {
        while (!isCompleted) {
            synchronized(lock) {
                try {
                    lock.wait(1000, 0)
                } catch (e: InterruptedException) {
                    // No-op.
                }
            }
        }
    }

    fun stop() {
        log.info("shutting down")
        _completed.set(true)

        synchronized(lock) {
            lock.notifyAll()
        }

        channel.shutdown()
        channel.awaitTermination(10, TimeUnit.SECONDS)
    }
}
