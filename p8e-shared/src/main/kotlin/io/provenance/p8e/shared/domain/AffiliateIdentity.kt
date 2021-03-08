package io.provenance.p8e.shared.domain

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import java.util.*

object AffiliateIdentityTable : IdTable<String>("affiliate_identity") {
    val publicKey = text("public_key").references(AffiliateTable.id)
    val identityUuid = uuid("identity_uuid")

    override val id: Column<EntityID<String>> = publicKey.entityId()
}

open class AffiliateIdentityEntityClass : EntityClass<String, AffiliateIdentityRecord>(AffiliateIdentityTable) {
    fun fromAffiliateRecord(affiliateRecord: AffiliateRecord, identityUuid: UUID) = new(affiliateRecord.publicKey.value) {
        this.identityUuid = identityUuid
    }
}

class AffiliateIdentityRecord(id: EntityID<String>): Entity<String>(id) {
    companion object: AffiliateIdentityEntityClass()

    var publicKey by AffiliateIdentityTable.id
    var identityUuid by AffiliateIdentityTable.identityUuid
}
