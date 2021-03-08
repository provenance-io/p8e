package io.p8e.grpc.observers

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.provenance.p8e.shared.extension.logger
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class QueueingStreamObserverSender<T>(
    val streamObserver: StreamObserver<T>
): Runnable, Closeable {
    private val queue = ConcurrentLinkedQueue<T>()
    private val errorQueue = ConcurrentLinkedQueue<Throwable>()
    private val end = AtomicBoolean(false)

    companion object {
        private val threadsRunning = AtomicInteger(0)
        private val totalThreadsStarted = AtomicInteger(0)
    }

    init {
        logger().info("Starting QueuingStreamObserverSender Thread: threadCount ${threadsRunning.incrementAndGet()}")
        thread(name = "QueuingStreamObserverSender-${totalThreadsStarted.incrementAndGet()}") {
            run()
            logger().info("Ending QueuingStreamObserverSender Thread: threadCount ${threadsRunning.decrementAndGet()}")
        }.also {
            it.setUncaughtExceptionHandler { t, e ->
                logger().info("(Exception) Ending QueuingStreamObserverSender Thread: threadCount ${threadsRunning.decrementAndGet()}")
            }
        }
    }

    override fun run() {
        while (true) {
            if (end.get()) {
                streamObserver.onCompleted()
                break
            }
            val error = errorQueue.poll()
            if (error != null) {
                // no conversion is needed for the error type because it already happened upstream
                streamObserver.onError(error)
                break
            }
            val value = queue.poll()
            if (value != null) {
                streamObserver.onNext(value)
            }

            // Yield context for a smidge
            Thread.sleep(10)
        }
    }

    fun queue(value: T) {
        queue.add(value)
    }

    fun error(sre: StatusRuntimeException) {
        errorQueue.add(sre)
    }

    override fun close() {
        end.set(true)
    }

    fun isClosed(): Boolean {
        return end.get()
    }
}
