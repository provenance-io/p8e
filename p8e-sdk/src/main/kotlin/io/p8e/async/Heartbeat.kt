package io.p8e.async

import io.grpc.StatusRuntimeException
import io.p8e.ContractManager
import io.p8e.grpc.observers.CompleteState
import io.p8e.grpc.observers.ExceptionState
import io.p8e.grpc.observers.NullState
import io.p8e.grpc.observers.QueueingStreamObserverSender
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.spec.P8eContract
import io.p8e.util.toHex
import io.provenance.p8e.shared.extension.logger
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

data class HeartbeatConnectionKey(
    val publicKey: PublicKey,
    val clazz: Class<out P8eContract>
)

class HeartbeatRunnable(
    private val queuers: ConcurrentHashMap<Class<out P8eContract>, QueueingStreamObserverSender<EnvelopeEvent>>,
    private val connections: ConcurrentHashMap<HeartbeatConnectionKey, EnvelopeEvent>,
) : Runnable {

    override fun run() {
        for ((key, event) in connections) {
            if (queuers[key.clazz]?.isClosed() == true) {
                connections.remove(key)
            } else {
                queuers[key.clazz]?.queue(event)
            }
        }
    }
}

class HeartbeatManagerRunnable(
    private val manager: ContractManager,
    private val queuers: ConcurrentHashMap<Class<out P8eContract>, QueueingStreamObserverSender<EnvelopeEvent>>,
    private val desired: ConcurrentHashMap<HeartbeatConnectionKey, () -> EnvelopeEventObserver<out P8eContract>>,
    private val actual: ConcurrentHashMap<HeartbeatConnectionKey, EnvelopeEvent>,
    private val loggingIterationThreshold: Long,
) : Runnable {

    private val log = logger()

    private var loggingIteration: Long = 0L

    override fun run() {
        if (loggingIteration >= loggingIterationThreshold) {
            log.info("desired size = ${desired.size} actual size = ${actual.size}")

            loggingIteration = 0
        } else {
            if (desired.size != actual.size) {
                log.info("desired size = ${desired.size} actual size = ${actual.size}")
            }

            loggingIteration += 1
        }

        val toAdd = desired.keys.filterNot { actual.containsKey(it) }
        val toRemove = actual.keys.filterNot { desired.containsKey(it) }

        if (toAdd.isNotEmpty()) {
            toAdd.find { key ->
                val lastEndState = queuers[key.clazz]?.lastEndState() ?: NullState

                when (lastEndState) {
                    is CompleteState, NullState -> false
                    is ExceptionState -> {
                        val throwable = lastEndState.t
                        if (throwable is StatusRuntimeException) {
                            when (throwable.status.code) {
                                io.grpc.Status.UNAVAILABLE.code -> true
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
                }
            }?.run {
                log.info("Sleeping for 10 seconds since last batch contains a network disconnect")
                Thread.sleep(10_000)
            }

            log.info("adding heartbeats for ${toAdd.map { it.publicKey.toHex() to it.clazz.name }}")

            for (key in toAdd) {
                val receiveObserver = desired.getValue(key).invoke()
                val sendObserver = QueueingStreamObserverSender(manager.client.event(key.clazz, receiveObserver))

                receiveObserver.setQueuer(sendObserver)
                queuers[key.clazz] = sendObserver
                actual[key] = manager.newEventBuilder(key.clazz.name, key.publicKey)
                    .setAction(EnvelopeEvent.Action.HEARTBEAT)
                    .build()
            }
        }

        if (toRemove.isNotEmpty()) {
            log.warn("removing heartbeats for ${toRemove.map { it.publicKey.toHex() to it.clazz.name }}")

            for (key in toRemove) {
                queuers.remove(key.clazz)?.close(CompleteState)
            }
        }
    }
}

