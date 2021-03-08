package io.provenance.p8e.shared.domain

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

object AffiliateToServiceTable : IdTable<String>("affiliate_service") {
    val affiliatePublicKey = reference("affiliate_public_key", AffiliateTable)
    val servicePublicKey = text("service_public_key").references(ServiceAccountTable.id)

    override val id: Column<EntityID<String>> = affiliatePublicKey
}

open class AffiliateToServiceEntityClass : EntityClass<String, AffiliateToServiceRecord>(AffiliateToServiceTable) {

}

class AffiliateToServiceRecord(id: EntityID<String>): Entity<String>(id) {
    companion object: AffiliateToServiceEntityClass()

    var affiliatePublicKey by AffiliateToServiceTable.affiliatePublicKey
    var servicePublicKey by AffiliateToServiceTable.servicePublicKey
}
