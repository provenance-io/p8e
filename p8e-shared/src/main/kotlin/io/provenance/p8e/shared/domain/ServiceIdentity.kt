package io.provenance.p8e.shared.domain

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import java.util.*

object ServiceIdentityTable : IdTable<String>("service_identity") {
    val publicKey = text("public_key").references(ServiceAccountTable.id)
    val identityUuid = uuid("identity_uuid")

    override val id: Column<EntityID<String>> = publicKey.entityId()
}

open class ServiceIdentityEntityClass : EntityClass<String, ServiceIdentityRecord>(ServiceIdentityTable) {
    fun fromServiceRecord(serviceRecord: ServiceAccountRecord, identityUuid: UUID) = new(serviceRecord.publicKey.value) {
        this.identityUuid = identityUuid
    }
}

class ServiceIdentityRecord(id: EntityID<String>): Entity<String>(id) {
    companion object: ServiceIdentityEntityClass()

    var publicKey by ServiceIdentityTable.id
    var identityUuid by ServiceIdentityTable.identityUuid
}
