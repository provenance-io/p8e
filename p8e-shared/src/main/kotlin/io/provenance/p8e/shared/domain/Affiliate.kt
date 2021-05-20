package io.provenance.p8e.shared.domain

import io.p8e.proto.Affiliate.AffiliateWhitelist
import io.p8e.util.toHex
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.util.proto
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.*
import java.security.KeyPair
import java.security.PublicKey
import java.util.*

const val DEFAULT_INDEX_NAME = "scopes"

object AffiliateTable : IdTable<String>("affiliate") {
    val alias = varchar("alias", 255).nullable()
    val publicKey = text("public_key")
    val privateKey = text("private_key").nullable()
    val whitelistData = proto("whitelist_data", AffiliateWhitelist.getDefaultInstance()).nullable()
    val encryptionPublicKey = text("encryption_public_key")
    val encryptionPrivateKey = text ("encryption_private_key").nullable()
    val indexName = varchar("index_name", 255).default(DEFAULT_INDEX_NAME)
    val active = bool("active").default(true)
    val keyUuid = uuid("key_uuid")
    val encryptionKeyUuid = uuid("encryption_key_uuid")
    val authPublicKey = text("auth_public_key")

    override val id: Column<EntityID<String>> = publicKey.entityId()
}

open class AffiliateEntityClass: EntityClass<String, AffiliateRecord>(
    AffiliateTable
) {
    fun findForUpdate(publicKey: PublicKey) = find { AffiliateTable.publicKey eq publicKey.toHex() }
        .forUpdate().firstOrNull()

    fun findByPublicKey(publicKey: PublicKey) = find { AffiliateTable.publicKey eq publicKey.toHex() }.firstOrNull()

    fun findByEncryptionPublicKey(publicKey: PublicKey) = find { AffiliateTable.encryptionPublicKey eq publicKey.toHex() }.firstOrNull()

    fun findByKeyUuid(uuid: String) = find{ AffiliateTable.keyUuid eq uuid.toUuidProv() }.firstOrNull()

    fun findManagedByPublicKey(publicKey: PublicKey, identityUuid: UUID) = AffiliateTable
        .join(AffiliateIdentityTable, JoinType.INNER, AffiliateTable.publicKey, AffiliateIdentityTable.publicKey)
        .slice(AffiliateTable.columns)
        .select { AffiliateIdentityTable.identityUuid eq identityUuid and (AffiliateTable.publicKey eq publicKey.toHex()) }
        .firstOrNull()
        ?.let { AffiliateRecord.wrapRow(it) }

    fun allByIdentityUuidQuery(identityUuid: UUID) = AffiliateTable
        .join(AffiliateIdentityTable, JoinType.INNER, AffiliateTable.publicKey, AffiliateIdentityTable.publicKey)
        .slice(AffiliateTable.columns)
        .select { AffiliateIdentityTable.identityUuid eq identityUuid }

    fun allByIdentityUuid(identityUuid: UUID) = allByIdentityUuidQuery(identityUuid)
        .map { AffiliateRecord.wrapRow(it) }

    fun getDistinctIndexNames() = AffiliateTable.slice(AffiliateTable.indexName).selectAll().withDistinct().map { it[AffiliateTable.indexName] };

    fun insert(signingKeyPair: KeyPair, encryptionKeyPair: KeyPair, authPublicKey: PublicKey, indexName: String?, alias: String? = null) =
        findForUpdate(signingKeyPair.public)
            ?: new(signingKeyPair.public.toHex()) {
                this.privateKey = signingKeyPair.private.toHex()
                this.encryptionPrivateKey = encryptionKeyPair.private.toHex()
                this.encryptionPublicKey = encryptionKeyPair.public.toHex()
                this.authPublicKey = authPublicKey.toHex()
                this.alias = alias
                indexName?.takeIf { it.isNotBlank() }?.let { this.indexName = it }
            }

    /**
     * Insert for an affiliate with just the signing public key.
     *
     * [signingPublicKey] The signing public key provided a key management system
     * [encryptionKeyPair] The encryptionKey pair of the affiliate
     * [indexName] Name of index for elasticsearch
     * [alias] Alias for affiliate
     */
    fun insert(signingPublicKey: ExternalKeyRef, encryptionPublicKey: ExternalKeyRef, authPublicKey: PublicKey, indexName: String?, alias: String? = null) =
        findForUpdate(signingPublicKey.publicKey)
            ?: new(signingPublicKey.publicKey.toHex()) {
                this.keyUuid = signingPublicKey.uuid
                this.encryptionPublicKey = encryptionPublicKey.publicKey.toHex()
                this.encryptionKeyUuid = encryptionPublicKey.uuid
                this.authPublicKey = authPublicKey.toHex()
                this.alias = alias
                indexName?.takeIf { it.isNotBlank() }?.let{ this.indexName = it }
            }
}

class AffiliateRecord(id: EntityID<String>): Entity<String>(id) {
    companion object: AffiliateEntityClass()

    var alias by AffiliateTable.alias
    var publicKey by AffiliateTable.id
    var privateKey by AffiliateTable.privateKey
    var whitelistData: AffiliateWhitelist? by AffiliateTable.whitelistData
    var encryptionPublicKey by AffiliateTable.encryptionPublicKey
    var encryptionPrivateKey by AffiliateTable.encryptionPrivateKey
    var indexName by AffiliateTable.indexName
    var active by AffiliateTable.active
    var serviceKeys by ServiceAccountRecord via AffiliateToServiceTable
    val identities by AffiliateIdentityRecord referrersOn AffiliateIdentityTable.publicKey
    var keyUuid by AffiliateTable.keyUuid
    var encryptionKeyUuid by AffiliateTable.encryptionKeyUuid
    var authPublicKey by AffiliateTable.authPublicKey
}
