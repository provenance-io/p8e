package io.provenance.p8e.shared.domain

import io.p8e.util.toHex
import io.provenance.p8e.shared.sql.offsetDatetime
import io.provenance.p8e.shared.domain.EnvelopeTable.default
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.select
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.time.OffsetDateTime
import java.util.*

class ServiceAccountStates {
    companion object {
        const val INITIALIZED = "INITIALIZED"
        const val OUT_OF_GAS = "OUT_OF_GAS"
    }
}

object ServiceAccountTable : IdTable<String>("service_accounts") {
    val privateKey = text("private_key")
    val publicKey = text("public_key")
    val status = varchar("status", 20)
    val alias = varchar("alias", 255).nullable()
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }

    override val id: Column<EntityID<String>> = publicKey.entityId()
}

open class ServiceAccountEntityClass: EntityClass<String, ServiceAccountRecord>(ServiceAccountTable) {
    fun findForUpdate(privateKey: PrivateKey) = find { ServiceAccountTable.privateKey eq privateKey.toHex() }
        .forUpdate().firstOrNull()

    fun findForUpdate(publicKey: PublicKey) = find { ServiceAccountTable.publicKey eq publicKey.toHex() }
        .forUpdate().firstOrNull()

    fun findByPublicKeys(publicKeys: List<PublicKey>) = find { ServiceAccountTable.publicKey inList publicKeys.map { it.toHex() }}

    fun allByIdentityUuid(identityUuid: UUID) = ServiceAccountTable
        .join(ServiceIdentityTable, JoinType.INNER, ServiceAccountTable.publicKey, ServiceIdentityTable.publicKey)
        .slice(ServiceAccountTable.columns)
        .select { ServiceIdentityTable.identityUuid eq identityUuid }
        .withDistinct()
        .map { ServiceAccountRecord.wrapRow(it) }

    fun insert(keyPair: KeyPair, status: String, alias: String? = null) = new(keyPair.public.toHex()) {
            this.privateKey = keyPair.private.toHex()
            this.status = status
            this.alias = alias
        }
}

class ServiceAccountRecord(id: EntityID<String>): Entity<String>(id) {
    companion object: ServiceAccountEntityClass()

    var privateKey by ServiceAccountTable.privateKey
    var publicKey by ServiceAccountTable.id
    var status by ServiceAccountTable.status
    var alias by ServiceAccountTable.alias
    var created by ServiceAccountTable.created
    val identities by ServiceIdentityRecord referrersOn ServiceIdentityTable.publicKey
}
