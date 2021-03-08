package io.provenance.engine.domain

import io.p8e.proto.ContractScope.Envelope.Status
import io.provenance.p8e.shared.sql.offsetDatetime
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.shared.domain.EnvelopeTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.and
import java.time.OffsetDateTime
import java.util.UUID

object EnvelopeStatusTable: UUIDTable(name = "envelope_status", columnName = "uuid") {
    val envelopeRecord = reference("envelope_uuid", EnvelopeTable)
    val publicKey = text("public_key")
    val status = enumerationByName("status", 256, Status::class)
    val created = offsetDatetime("created")
    val updated = offsetDatetime("updated").nullable()
}

open class EnvelopeStatusEntityClass : UUIDEntityClass<EnvelopeStatusRecord>(EnvelopeStatusTable) {

    fun insert(
        envelopeRecord: EnvelopeRecord,
        publicKey: String,
        status: Status,
        created: OffsetDateTime = OffsetDateTime.now()
    ): EnvelopeStatusRecord {
        return new(UUID.randomUUID()) {
            this.envelopeRecord = envelopeRecord
            this.publicKey = publicKey
            this.status = status
            this.created = created
        }
    }

    fun findForUpdate(
        envelopeUuid: UUID,
        publicKey: String
    ): EnvelopeStatusRecord? {
        return find {
            (EnvelopeStatusTable.envelopeRecord eq envelopeUuid) and
            (EnvelopeStatusTable.publicKey eq publicKey)
        }.forUpdate()
        .firstOrNull()
    }

    fun update(
        envelopeUuid: UUID,
        publicKey: String,
        status: Status
    ): EnvelopeStatusRecord? {
        return findForUpdate(envelopeUuid, publicKey)
            ?.apply { this.status = status }
    }
}

class EnvelopeStatusRecord(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : EnvelopeStatusEntityClass()

    var envelopeRecord by EnvelopeRecord referencedOn EnvelopeStatusTable.envelopeRecord
    var publicKey: String by EnvelopeStatusTable.publicKey
    var status by EnvelopeStatusTable.status
    var created by EnvelopeStatusTable.created
    var updated by EnvelopeStatusTable.updated
}
