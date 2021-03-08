package io.provenance.p8e.shared.domain

import io.p8e.util.toHex
import io.p8e.util.toJavaPublicKey
import io.provenance.p8e.shared.sql.offsetDatetime
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.and
import java.security.PublicKey
import java.time.OffsetDateTime
import java.util.UUID

data class AffiliateSharePublicKeys(val value: Set<PublicKey>)

object AffiliateShareTable : UUIDTable(name = "affiliate_share", columnName = "uuid") {
    val affiliatePublicKey = text("affiliate_public_key")
    val publicKey = text("public_key")
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }
}

open class AffiliateShareEntityClass: UUIDEntityClass<AffiliateShareRecord>(
    AffiliateShareTable
) {
    fun findByAffiliate(publicKey: PublicKey) = find { AffiliateShareTable.affiliatePublicKey eq publicKey.toHex() }

    fun findByAffiliateAndPublicKey(affiliatePublicKey: PublicKey, publicKey: PublicKey) = find {
        (AffiliateShareTable.affiliatePublicKey eq affiliatePublicKey.toHex()) and (AffiliateShareTable.publicKey eq publicKey.toHex())
    }.firstOrNull()

    fun insert(affiliatePublicKey: PublicKey, publicKey: PublicKey) = new {
        this.affiliatePublicKey = affiliatePublicKey.toHex()
        this.publicKey = publicKey.toHex()
    }

    fun findByAffiliates(publicKeys: Collection<PublicKey>) = find {
        AffiliateShareTable.affiliatePublicKey inList publicKeys.map { it.toHex() }
    }
}

class AffiliateShareRecord(uuid: EntityID<UUID>): UUIDEntity(uuid) {
    companion object: AffiliateShareEntityClass()

    var uuid by AffiliateShareTable.id
    var affiliatePublicKey by AffiliateShareTable.affiliatePublicKey
    var publicKey by AffiliateShareTable.publicKey
    var created by AffiliateShareTable.created

    fun typedAffiliatePublicKey(): PublicKey = affiliatePublicKey.toJavaPublicKey()
    fun typedPublicKey(): PublicKey = publicKey.toJavaPublicKey()
}
