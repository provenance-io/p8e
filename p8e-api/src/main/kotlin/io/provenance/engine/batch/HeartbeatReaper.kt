package io.provenance.engine.batch

import io.p8e.util.toJavaPublicKey
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.domain.AffiliateConnectionRecord
import io.provenance.engine.domain.ConnectionStatus
import io.provenance.engine.grpc.v1.EnvelopeGrpc
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class HeartbeatReaper(
    private val envelopeGrpc: EnvelopeGrpc
) {

    @Scheduled(initialDelayString = "\${reaper.heartbeat.delay}", fixedDelayString = "\${reaper.heartbeat.interval}")
    fun reap() {
        transaction {
            AffiliateConnectionRecord.findByLastHeartbeatOlderThan(7)
                .forUpdate()
                .forEach { affiliateConnection ->
                    logger().info("Stale heartbeat detected for public key ${affiliateConnection.id.value} classname ${affiliateConnection.classname}")
                    affiliateConnection.connectionStatus = ConnectionStatus.NOT_CONNECTED
                    envelopeGrpc.removeObserver(
                        affiliateConnection.publicKey.toJavaPublicKey(),
                        affiliateConnection.classname
                    )
                }
        }
    }
}
