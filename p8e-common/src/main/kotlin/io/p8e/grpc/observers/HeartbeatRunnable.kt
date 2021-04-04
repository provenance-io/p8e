package io.p8e.grpc.observers

import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.spec.P8eContract
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

data class HeartbeatConnectionKey(
    val publicKey: PublicKey,
    val clazz: Class<out P8eContract>
)

data class HeartbeatQueuer(
    val heartbeatEvent: EnvelopeEvent,
    val queuer: QueueingStreamObserverSender<EnvelopeEvent>
)

class HeartbeatRunnable(
    private val connections: ConcurrentHashMap<HeartbeatConnectionKey, HeartbeatQueuer>
) : Runnable {

    override fun run() {
        for ((_, connection) in connections) {
            // skip closed stream observers until they are cleaned up external to this thread
            if (connection.queuer.isClosed()) {
                return
            }

            connection.queuer.queue(connection.heartbeatEvent)
        }
    }
}
