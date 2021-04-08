package io.p8e.grpc.observers

import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.provenance.p8e.shared.extension.logger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

sealed class EndState
object NullState : EndState()
data class ExceptionState(val t: Throwable) : EndState()
object CompleteState : EndState()

class QueueingStreamObserverSender<T>(
    val streamObserver: StreamObserver<T>
): Runnable {
    private val queue = ConcurrentLinkedQueue<T>()
    private val errorQueue = ConcurrentLinkedQueue<Throwable>()
    private val end = AtomicReference<EndState>(NullState)

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
            if (isClosed()) {
                streamObserver.onCompleted()
                break
            }
            val error = errorQueue.poll()
            if (error != null) {
                // no conversion is needed for the error type because it already happened upstream
                streamObserver.onError(error)
                close(ExceptionState(error))
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

    fun close(state: EndState) {
        end.set(state)
    }

    fun lastEndState(): EndState {
        return end.get()
    }

    fun isClosed(): Boolean {
        return end.get() != NullState
    }
}
