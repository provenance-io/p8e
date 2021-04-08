package io.p8e.async

import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.p8e.grpc.observers.CompleteState
import io.p8e.grpc.observers.EndState
import io.p8e.grpc.observers.ExceptionState
import io.p8e.grpc.observers.NullState
import io.p8e.grpc.observers.QueueingStreamObserverSender
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.spec.P8eContract
import io.provenance.p8e.shared.extension.logger
import java.util.concurrent.atomic.AtomicReference

class EnvelopeEventObserver<T: P8eContract>(
    private val clazz: Class<T>,
    private val handler: (Class<T>, EnvelopeEvent) -> Unit,
): StreamObserver<EnvelopeEvent> {

    private val log = logger()

    private val queuer = AtomicReference<QueueingStreamObserverSender<EnvelopeEvent>?>(null)
    private val preQueuerCloseState = AtomicReference<EndState>(NullState)

    fun setQueuer(queueingStreamObserverSender: QueueingStreamObserverSender<EnvelopeEvent>) {
        synchronized(this) {
            queuer.set(queueingStreamObserverSender)

            if (preQueuerCloseState.get() != NullState) {
                log.debug("Closing stream observer early due to exception coming in before queuer was set")

                queuer.get()!!.close(preQueuerCloseState.get())
            }
        }
    }

    override fun onNext(value: EnvelopeEvent) {
        handler.invoke(clazz, value)
    }

    override fun onError(t: Throwable) {
        if (t is StatusRuntimeException) {
            when (t.status.code) {
                io.grpc.Status.UNAVAILABLE.code -> Unit
                else -> log.warn("Received Throwable on event observer", t)
            }
        }

        val state = ExceptionState(t)

        synchronized(this) {
            queuer.get()?.close(state) ?: preQueuerCloseState.set(state)
        }
    }

    override fun onCompleted() {
        log.warn("onCompleted received event observer")

        synchronized(this) {
            queuer.get()?.close(CompleteState) ?: preQueuerCloseState.set(CompleteState)
        }
    }
}
