package io.p8e.async

import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.p8e.functional.DisconnectHandlerWrapper
import io.p8e.grpc.observers.QueueingStreamObserverSender
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.spec.P8eContract
import io.p8e.util.ThreadPoolFactory
import io.provenance.p8e.shared.extension.logger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class EnvelopeEventObserver<T: P8eContract>(
    private val clazz: Class<T>,
    private val handler: (Class<T>, EnvelopeEvent) -> Unit,
    private val disconnectHandler: DisconnectHandlerWrapper<T>,
    private val clearQueuerAndHandlers: () -> Unit,
    private val reconnectHandler: (StreamObserver<EnvelopeEvent>) -> StreamObserver<EnvelopeEvent>
): StreamObserver<EnvelopeEvent> {
    val queuer = AtomicReference<QueueingStreamObserverSender<EnvelopeEvent>>()

    private val reconnectCount = AtomicLong(0)
    private val log = logger()

    override fun onNext(value: EnvelopeEvent) {
        handler.invoke(clazz, value)
    }

    override fun onError(t: Throwable) {
        clearQueuerAndHandlers()
        when (t) {
            is StatusRuntimeException -> {
                when (t.status.code) {
                    Status.UNKNOWN.code,
                    Status.UNAVAILABLE.code -> {
                        log.info("Protocol level disconnect, reconnecting in ${reconnectCount.incrementAndGet() * 2} seconds.", t)
                        executor.schedule(
                            thread(start = false) {
                                reconnectHandler(this)
                            },
                            reconnectCount.get() * 2,
                            TimeUnit.SECONDS
                        )
                    }
                    else -> t.also {
                        log.info("Received StatusRuntimeException on event observer - firing disconnectHandler", t)
                        disconnectHandler.handle()
                    }
                }
            }
            else -> t.also{
                log.info("Received Throwable on event observer - firing disconnectHandler", t)
                disconnectHandler.handle()
            }
        }
    }

    override fun onCompleted() {
        log.info("onCompleted received event observer")
        disconnectHandler.handle()
    }

    companion object {
        private val executor = ThreadPoolFactory.newScheduledThreadPool(8, "envelope-event-%d")
    }
}
