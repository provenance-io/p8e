package io.provenance.engine.domain

import io.p8e.util.orThrow
import io.p8e.util.toHex
import io.provenance.p8e.shared.sql.offsetDatetime
import io.provenance.engine.domain.ConnectionStatus.NOT_CONNECTED
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.and
import java.security.PublicKey
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import io.p8e.util.AffiliateConnectionException
import java.util.UUID

object AffiliateConnectionTable: UUIDTable("affiliate_connection", columnName = "uuid") {
    val classname = text("classname")
    val connectionStatus = enumerationByName("connection_status", 256, ConnectionStatus::class)
    val lastHeartbeat = offsetDatetime("last_heartbeat").nullable()
    val publicKey = text("public_key")
}

open class AffiliateConnectionEntityClass: UUIDEntityClass<AffiliateConnectionRecord>(AffiliateConnectionTable) {
    fun findForUpdate(uuid: UUID) = find { AffiliateConnectionTable.id eq uuid }.forUpdate().firstOrNull()

    fun findForUpdate(
        publicKey: PublicKey,
        classname: String
    ): AffiliateConnectionRecord? {
        return find {
            (AffiliateConnectionTable.publicKey eq publicKey.toHex()) and
            (AffiliateConnectionTable.classname eq classname)
        }.forUpdate()
        .firstOrNull()
    }

    fun findOrCreateForUpdate(
        publicKey: PublicKey,
        classname: String
    ): AffiliateConnectionRecord {
        return findForUpdate(publicKey, classname) ?: AffiliateConnectionRecord.new(UUID.randomUUID()) {
            this.publicKey = publicKey.toHex()
            this.classname = classname
            this.connectionStatus = NOT_CONNECTED
        }
    }

    fun findConnected() = find {
        AffiliateConnectionTable.connectionStatus eq ConnectionStatus.CONNECTED
    }

    fun heartbeat(
        publicKey: PublicKey,
        classname: String
    ) {
        findForUpdate(publicKey, classname)
            .orThrow { IllegalStateException("Received a heartbeat for a public key not associated with an affiliate ${publicKey.toHex()}") }
            .takeIf { it.connectionStatus == ConnectionStatus.CONNECTED }
            .orThrow { AffiliateConnectionException("Received a heartbeat for an affiliate but it is not currently in the ${ConnectionStatus.CONNECTED.name} state. This could be possible during some race conditions and should disconnect and reconnect automatically assuming a correct disconnect/reconnect implementation.") }
            .lastHeartbeat = OffsetDateTime.now()
    }

    fun disconnect(
        publicKey: PublicKey,
        classname: String
    ) {
        findForUpdate(publicKey, classname)
            .orThrow { IllegalStateException("Received a heartbeat for a public key not associated with an affiliate ${publicKey.toHex()}") }
            .connectionStatus = NOT_CONNECTED
    }

    fun findByLastHeartbeatOlderThan(
        olderThanSeconds: Long
    ): SizedIterable<AffiliateConnectionRecord> {
        return AffiliateConnectionRecord.find {
            (AffiliateConnectionTable.lastHeartbeat lessEq OffsetDateTime.now().minus(olderThanSeconds, ChronoUnit.SECONDS)) and
            (AffiliateConnectionTable.connectionStatus eq ConnectionStatus.CONNECTED)
        }
    }
}

class AffiliateConnectionRecord(uuid: EntityID<UUID>): UUIDEntity(uuid) {
    companion object: AffiliateConnectionEntityClass()

    var publicKey by AffiliateConnectionTable.publicKey
    var classname by AffiliateConnectionTable.classname
    var connectionStatus: ConnectionStatus by AffiliateConnectionTable.connectionStatus
    var lastHeartbeat: OffsetDateTime? by AffiliateConnectionTable.lastHeartbeat
}

enum class ConnectionStatus {
    NOT_CONNECTED,
    CONNECTED
}
