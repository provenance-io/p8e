package io.provenance.engine.domain

import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.provenance.p8e.shared.sql.offsetDatetime
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.domain.EnvelopeTable
import io.provenance.p8e.shared.util.protoBytes
import io.p8e.proto.Util
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import java.time.OffsetDateTime
import java.util.UUID

fun P8eEvent.toUuid() = Util.UUID.parseFrom(this.message).toUuidProv()

object EventTable : UUIDTable(name = "event", columnName = "uuid") {
    val payload = protoBytes("payload", P8eEvent.getDefaultInstance())
    val event = enumerationByName("event", 256, Event::class)
    val status = enumerationByName("status", 256, EventStatus::class)
    val envelopeUuid = uuid("envelope_uuid").references(EnvelopeTable.id).index()
    val created = offsetDatetime("created")
    val updated = offsetDatetime("updated").nullable()
}

open class EventEntityClass : UUIDEntityClass<EventRecord>(EventTable) {
    fun findForUpdate(uuid: UUID) = find { EventTable.id eq uuid }.forUpdate().firstOrNull()

    fun insertOrUpdate(
        event: P8eEvent,
        envelopeUuid: UUID,
        status: EventStatus = EventStatus.CREATED,
        created: OffsetDateTime = OffsetDateTime.now(),
        updated: OffsetDateTime = OffsetDateTime.now()
    ): EventRecord = findByEnvelopeUuidForUpdate(envelopeUuid)?.also {
        it.event = event.event
        it.payload = event
        it.status = status
        it.created = created
        it.updated = updated
    } ?: new(UUID.randomUUID()) {
            this.event = event.event
            this.payload = event
            this.status = status
            this.envelopeUuid = envelopeUuid
            this.created = created
            this.updated = updated
    }


    fun findByEvent(event: P8eEvent.Event): List<EventRecord> =
        find{
            (EventTable.event eq event)
        }.toList()

    fun findForConnectedClients(where: (SqlExpressionBuilder.()-> Op<Boolean>)) = EventTable
        .innerJoin(EnvelopeTable)
        .join(AffiliateConnectionTable, JoinType.INNER, EnvelopeTable.contractClassname, AffiliateConnectionTable.classname) {
            (AffiliateConnectionTable.publicKey eq EnvelopeTable.publicKey) and (AffiliateConnectionTable.connectionStatus eq ConnectionStatus.CONNECTED)
        }.slice(EventTable.columns)
        .select(where)
        .mapLazy { wrapRow(it) }

    fun findByEnvelopeUuidForUpdate(envelopeUuid: UUID) = find { EventTable.envelopeUuid eq envelopeUuid }.forUpdate().firstOrNull()
}

class EventRecord(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : EventEntityClass()

    var eventUuid by EventTable.id
    var payload by EventTable.payload
    var event by EventTable.event
    var envelopeUuid by EventTable.envelopeUuid
    var status by EventTable.status
    var created by EventTable.created
    var updated by EventTable.updated
}

enum class EventStatus {
    CREATED,
    COMPLETE,
    ERROR
}
