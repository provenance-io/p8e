package io.provenance.engine.domain

import io.provenance.p8e.shared.sql.offsetDatetime
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.UUIDTable
import java.time.OffsetDateTime
import java.util.UUID

typealias EST = EventStreamTable
object EventStreamTable: UUIDTable(name = "event_stream", columnName = "uuid") {
    val lastBlockHeight = long("last_block_height")
    val created = offsetDatetime("created")
    val updated = offsetDatetime("updated").nullable()
}

open class EventStreamEntity : UUIDEntityClass<EventStreamRecord>(EventStreamTable) {

    fun find(id: UUID) =
        findById(id) ?: throw IllegalStateException("Unable to find event stream with uuid $id")

    fun insert(id: UUID, lastBlockHeight: Long) =
        new(id) {
            this.lastBlockHeight = lastBlockHeight
            this.created = OffsetDateTime.now()
        }

    fun update(id: UUID, lastBlockHeight: Long) =
        find(id).apply {
            this.lastBlockHeight = lastBlockHeight
            this.updated = OffsetDateTime.now()
        }
}

class EventStreamRecord(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : EventStreamEntity()
    var uuid by EventStreamTable.id
    var lastBlockHeight by EventStreamTable.lastBlockHeight
    var created by EventStreamTable.created
    var updated by EventStreamTable.updated
}
