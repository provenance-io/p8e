package io.provenance.p8e.shared.service

import io.p8e.crypto.SignerFactory
import io.p8e.crypto.SignerFactoryParam
import io.p8e.crypto.SignerImpl
import io.p8e.crypto.Hash
import io.p8e.proto.Affiliate.AffiliateContractWhitelist
import io.p8e.proto.Affiliate.AffiliateWhitelist
import io.p8e.util.*
import io.p8e.util.auditedProv
import io.p8e.util.toProtoTimestampProv
import io.provenance.engine.crypto.Bech32
import io.provenance.engine.crypto.toBech32Data
import io.provenance.p8e.shared.extension.isActive
import io.provenance.os.client.OsClient
import io.provenance.p8e.encryption.model.ExternalKeyRef
import io.provenance.p8e.encryption.model.KeyProviders.DATABASE
import io.provenance.p8e.encryption.model.KeyProviders.SMARTKEY
import io.provenance.p8e.encryption.model.KeyRef
import io.provenance.p8e.shared.domain.*
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.client.indices.CreateIndexRequest
import org.elasticsearch.client.indices.GetIndexRequest
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.KeyPair
import java.security.PublicKey
import java.time.OffsetDateTime
import java.util.*

@Component
class AffiliateService(
    private val cacheManager: CacheManager,
    private val osClient: OsClient,
    private val keystoneService: KeystoneService,
    private val esClient: RestHighLevelClient,
    private val signerFactory: SignerFactory,
) {

    companion object {
        const val AFFILIATE = "affiliate"
        const val AFFILIATE_FIRST = "affiliate_first"
        const val AFFILIATE_KEY_PAIR = "affiliate_key_pair"
        const val AFFILIATE_SIGNING_KEY_PAIR = "affiliate_signing_key_pair"
        const val AFFILIATE_PEN = "affiliate_pen"
        const val AFFILIATES = "affiliates"
        const val AFFILIATES_ENCRYPTION_KEYS = "affiliates_encryption_keys"
        const val AFFILIATES_SIGNING_KEYS = "affiliates_signing_keys"
        const val AFFILIATE_INDEX_NAMES = "affiliate_index_names"
        const val AFFILIATE_INDEX_NAME = "affiliate_index_name"
        const val AFFILIATE_ENCRYPTION_KEY_PAIR = "affiliate_encryption_key_pair"
        const val AFFILIATE_BECH32_LOOKUP = "affiliate_bech32_lookup"
        const val PUBLIC_KEY_TO_ADDRESS = "public_key_to_address"
        const val AFFILIATE_SIGNING_KID = "affiliate_signing_key_id"
        const val AFFILIATE_ENCRYPTION_PUBLIC_KEY = "affiliate_encryption_public_key"
    }

    /**
     *  Return the signer to be used
     *
     *  [publicKey] publicKey used to validate
     *  [signer] The specific signer used to sign contracts
     */
    fun getSigner(publicKey: PublicKey): SignerImpl {
        val affiliateRecord = get(publicKey)
            .orThrowNotFound("Affiliate Record Not found")

        return when(affiliateRecord.keyType) {
            SMARTKEY -> signerFactory.getSigner(SignerFactoryParam.SmartKeyParam(affiliateRecord.signingKeyUuid.toString(), affiliateRecord.publicKey.value.toJavaPublicKey()))
            DATABASE -> signerFactory.getSigner(SignerFactoryParam.PenParam(KeyPair(affiliateRecord.publicKey.value.toJavaPublicKey(), affiliateRecord.privateKey!!.toJavaPrivateKey())))
        }
    }

    fun getEncryptionKeyRef(publicKey: PublicKey): KeyRef {
        val affiliateRecord = getFirst(publicKey)
        return KeyRef(
            affiliateRecord.encryptionPublicKey.toJavaPublicKey(),
            affiliateRecord.encryptionPrivateKey?.toJavaPrivateKey(),
            affiliateRecord.encryptionKeyUuid,
            affiliateRecord.keyType
        )
    }

    /**
     * Get affiliate by pk.
     *
     * @param [publicKey] primary key
     * @return the [AffiliateRecord] found or null
     */
    @Cacheable(AFFILIATE)
    fun get(publicKey: PublicKey): AffiliateRecord? = AffiliateRecord.findById(publicKey.toHex())
            ?: AffiliateRecord.findByEncryptionPublicKey(publicKey)
            ?: AffiliateRecord.findByAuthenticationPublicKey(publicKey)

    /**
     * Get affiliate by UUID.
     *
     * @param [uuid] keypair identifier.
     * @return the [AffiliateRecord] found or null
     */
    @Cacheable(AFFILIATE)
    fun get(uuid: String): AffiliateRecord? = AffiliateRecord.findById(uuid)

    /**
     * Get affiliate by pk, not nullable.
     *
     * @param [uuid] primary key
     * @throws [NotFoundException] If null, exceptions
     * @return the [AffiliateRecord] found
     */
    @Cacheable(AFFILIATE_FIRST)
    internal fun getFirst(publicKey: PublicKey): AffiliateRecord = get(publicKey)
        ?: throw NotFoundException("Public Key is not a valid signing or encryption key: ${publicKey.toHex()}")

    /**
     * Get affiliate by pk, not nullable.
     *
     * @param [uuid] primary key
     * @throws [NotFoundException] If null, exceptions
     * @return the [AffiliateRecord] found
     */
    @Cacheable(AFFILIATE_FIRST)
    internal fun getFirst(uuid: String): AffiliateRecord = get(uuid)
        ?: throw NotFoundException("Key UUID is not found: $uuid")

    /**
     * Get encryption key pair for an affiliate.
     *
     * @param [uuid] primary key
     * @param [certificateUuid] certificate to use
     * @throws [NotFoundException] If null, exceptions
     * @return the [KeyPair] derived from affiliate encryption
     */
    @Cacheable(AFFILIATE_KEY_PAIR)
    fun getSigningKeyPair(publicKey: PublicKey): KeyPair {
        val affiliateRecord = getFirst(publicKey)
        return KeyPair(affiliateRecord.publicKey.value.toJavaPublicKey(), affiliateRecord.privateKey?.toJavaPrivateKey())
    }

    @Cacheable(AFFILIATE_ENCRYPTION_KEY_PAIR)
    fun getEncryptionKeyPair(publicKey: PublicKey): KeyPair {
        val affiliateRecord = getFirst(publicKey)
        return KeyPair(affiliateRecord.encryptionPublicKey.toJavaPublicKey(), affiliateRecord.encryptionPrivateKey?.toJavaPrivateKey())
    }
    
    @Cacheable(AFFILIATE_KEY_PAIR)
    fun getSigningKeyPair(uuid: String): KeyPair{
        val affiliateRecord = getFirst(uuid)
        return KeyPair(affiliateRecord.publicKey.value.toJavaPublicKey(), affiliateRecord.privateKey?.toJavaPrivateKey())
    }

    fun getSigningPublicKey(publicKey: PublicKey): PublicKey = getFirst(publicKey).publicKey.value.toJavaPublicKey()

    /**
     * Validate that the public key is a valid affiliate against the database.
     *
     * @param [publicKey] Public key of the contract being executed.
     * @return [boolean] return true if the public key exists in the affiliate table.
     */
    @Cacheable(AFFILIATES_ENCRYPTION_KEYS)
    fun getEncryptionPublicKey(publicKey: PublicKey): PublicKey {
        val affiliateRecord = getFirst(publicKey)
        return affiliateRecord.encryptionPublicKey.toJavaPublicKey()
    }

    @Cacheable(AFFILIATE_BECH32_LOOKUP)
    fun getAffiliateFromBech32Address(bech32Address: String): AffiliateRecord? {
        val mainNet = bech32Address.startsWith(Bech32.PROVENANCE_MAINNET_ACCOUNT_PREFIX)
        return getAll()
            .find { affiliate ->
                getAddress(affiliate.publicKey.value.toJavaPublicKey(), mainNet) == bech32Address || getAddress(affiliate.encryptionPublicKey.toJavaPublicKey(), mainNet) == bech32Address
            }
    }

    @Cacheable(PUBLIC_KEY_TO_ADDRESS)
    private fun getAddress(publicKey: PublicKey, mainNet: Boolean): String =
        publicKey.let {
            (it as BCECPublicKey).q.getEncoded(true)
        }.let {
            Hash.sha256hash160(it)
        }.let {
            val prefix = if (mainNet) Bech32.PROVENANCE_MAINNET_ACCOUNT_PREFIX else Bech32.PROVENANCE_TESTNET_ACCOUNT_PREFIX
            it.toBech32Data(prefix).address
        }

    /**
     * Get all affiliates.
     *
     * @return the [AffiliateRecord] list
     */
    @Cacheable(AFFILIATES)
    fun getAll(): List<AffiliateRecord> = AffiliateRecord.all().toList()

    /**
     * Get all distinct index names
     *
     * @return the list of index names
     */
    @Cacheable(AFFILIATE_INDEX_NAMES)
    fun getAllIndexNames() = AffiliateRecord.getDistinctIndexNames()

    /**
     * Get index name from public key
     *
     * @return the index name for the specified public key
     */
    @Cacheable(AFFILIATE_INDEX_NAME)
    fun getIndexNameByPublicKey(publicKey: PublicKey) = get(publicKey)?.indexName ?: DEFAULT_INDEX_NAME

    /**
     * Save or update an affiliate.
     *
     * @param [signingKeyPair] The signing key pair for data signing
     * @param [encryptionKeyPair] The encryption used for affiliate auth
     * @param [indexName] Name of index for doc storage.
     * @param [alias] alias used to describe an affiliate.
     * @param [jwt] token for webservice authentication.
     * @return [AffiliateRecord] The affiliate record
     */
    @CacheEvict(cacheNames = [
        AFFILIATE,
        AFFILIATE_FIRST,
        AFFILIATE_KEY_PAIR,
        AFFILIATE_PEN,
        AFFILIATES,
        AFFILIATES_ENCRYPTION_KEYS,
        AFFILIATES_SIGNING_KEYS,
        AFFILIATE_INDEX_NAMES,
        AFFILIATE_INDEX_NAME,
        AFFILIATE_BECH32_LOOKUP,
    ])

    fun save(signingKeyPair: KeyPair, encryptionKeyPair: KeyPair, authPublicKey: PublicKey, indexName: String? = null, alias: String? = null): AffiliateRecord =
        AffiliateRecord.insert(signingKeyPair, encryptionKeyPair, authPublicKey, indexName, alias)
            .also {
                // Register the key with object store so that it monitors for replication.
                osClient.createPublicKey(signingKeyPair.public, encryptionKeyPair.public)

                // create index in ES if it doesn't already exist
                indexName?.let {
                    if (!esClient.indices().exists(GetIndexRequest(it), RequestOptions.DEFAULT)) {
                        val response = esClient.indices().create(CreateIndexRequest(it), RequestOptions.DEFAULT)
                        require (response.isAcknowledged) { "ES index creation of $it was not successful" }
                    }
                }
            }


    /**
     * Save or update an affiliate with a signing public key from a key management system.
     *
     * @param [signingKeyRef] The signing key reference used for signing
     * @param [encryptionKeyRef] The encryption key reference used for encryption
     * @param [authPublicKey] The public key used for validating auth
     * @param [indexName] Name of index for doc storage.
     * @param [alias] alias used to describe an affiliate.
     * @param [jwt] token for webservice authentication.
     * @return [AffiliateRecord] The affiliate record
     */
    @CacheEvict(cacheNames = [
        AFFILIATE,
        AFFILIATE_FIRST,
        AFFILIATE_KEY_PAIR,
        AFFILIATE_PEN,
        AFFILIATES,
        AFFILIATES_ENCRYPTION_KEYS,
        AFFILIATES_SIGNING_KEYS,
        AFFILIATE_INDEX_NAMES,
        AFFILIATE_INDEX_NAME,
        AFFILIATE_BECH32_LOOKUP,
    ])
    fun save(signingKeyRef: ExternalKeyRef, encryptionKeyRef: ExternalKeyRef, authPublicKey: PublicKey, indexName: String? = null, alias: String?, jwt: String? = null, identityUuid: UUID? = null): AffiliateRecord =
        AffiliateRecord.insert(signingKeyRef, encryptionKeyRef, authPublicKey, indexName, alias)
            .also {
                // Register the key with object store so that it monitors for replication.
                osClient.createPublicKey(signingKeyRef.publicKey, encryptionKeyRef.publicKey)

                // create index in ES if it doesn't already exist
                indexName?.let {
                    if (!esClient.indices().exists(GetIndexRequest(it), RequestOptions.DEFAULT)) {
                        val response = esClient.indices().create(CreateIndexRequest(it), RequestOptions.DEFAULT)
                        require (response.isAcknowledged) { "ES index creation of $it was not successful" }
                    }
                }
            }

    fun attachServiceKeys(affiliatePublicKey: PublicKey, servicePublicKeys: List<PublicKey>) = servicePublicKeys.map { serviceKey ->
        AffiliateToServiceRecord.new(affiliatePublicKey.toHex()) {
            servicePublicKey = serviceKey.toHex()
        }
    }.let {
        ServiceAccountRecord.findByPublicKeys(servicePublicKeys).toList()
    }

    fun removeServiceKeys(affiliatePublicKey: PublicKey, servicePublicKeys: List<PublicKey>) = AffiliateToServiceTable.deleteWhere {
        (AffiliateToServiceTable.affiliatePublicKey eq affiliatePublicKey.toHex()) and (AffiliateToServiceTable.servicePublicKey inList servicePublicKeys.map { it.toHex() })
    }

    /**
     * Get all encryption key pairs mapped by public key.
     *
     * @return the [AffiliateKeyPair] map.
     */
    @Cacheable(AFFILIATES_ENCRYPTION_KEYS)
    fun getEncryptionKeyPairs(): Map<String, KeyPair> = getAll()
        .map {
            // We need to handle nullable keys
            it.encryptionPublicKey to
                    KeyPair(it.encryptionPublicKey.toJavaPublicKey(), it.encryptionPrivateKey?.toJavaPrivateKey())
        }.toMap()

    /**
     * Get whitelists data for an affiliate.
     *
     * @param [uuid] primary key
     * @return the [AffiliateWhitelist] or default
     */
    fun getWhitelists(publicKey: PublicKey): AffiliateWhitelist = getFirst(publicKey).whitelistData ?: AffiliateWhitelist.getDefaultInstance()

    /**
     * Get whitelist contract for an affiliate if its active.
     *
     * @param [uuid] primary key
     * @return the active [AffiliateContractWhitelist]
     */
    fun getWhitelistContractByClassNameActive(publicKey: PublicKey, className: String): AffiliateContractWhitelist? = getWhitelists(publicKey)
        .contractWhitelistsList
        .firstOrNull { it.classname == className && it.isActive() }

    /**
     * Save class to affiliate class whitelist.
     *
     * @param [uuid] primary key
     * @param [affiliateContractWhitelist] The contract to add to whitelist
     */
    @CacheEvict(cacheNames = [
        AFFILIATE,
        AFFILIATE_FIRST,
        AFFILIATES
    ])
    fun addWhitelistClass(publicKey: PublicKey, affiliateContractWhitelist: AffiliateContractWhitelist): AffiliateWhitelist? {
        val signingPublicKey = get(publicKey)!!.publicKey.value.toJavaPublicKey()
        return AffiliateRecord.findForUpdate(signingPublicKey)!!
            .also {
                val builder = it.whitelistData?.toBuilder() ?: AffiliateWhitelist.newBuilder()

                if (builder.contractWhitelistsBuilderList.none { whitelist ->
                        whitelist.classname == affiliateContractWhitelist.classname && whitelist.build().isActive()
                    }) {
                    it.whitelistData = builder
                        .addContractWhitelists(
                            affiliateContractWhitelist.toBuilder().setStartTime(OffsetDateTime.now().toProtoTimestampProv())
                        )
                        .auditedProv()
                        .build()
                }
            }
            .whitelistData
    }

    fun getSharePublicKeys(publicKeys: Collection<PublicKey>): AffiliateSharePublicKeys =
        AffiliateShareRecord.findByAffiliates(publicKeys)
            .map { it.typedPublicKey() }
            .toSet()
            .let(::AffiliateSharePublicKeys)

    fun getShares(affiliatePublicKey: PublicKey): List<AffiliateShareRecord> = AffiliateShareRecord.findByAffiliate(affiliatePublicKey).toList()

    fun addShare(affiliatePublicKey: PublicKey, publicKey: PublicKey) = AffiliateShareRecord.insert(affiliatePublicKey, publicKey)

    fun removeShare(affiliatePublicKey: PublicKey, publicKey: PublicKey) = AffiliateShareRecord.findByAffiliateAndPublicKey(affiliatePublicKey, publicKey)?.delete()

    /**
     * Eviction of caches on a timer.
     */
    @Scheduled(fixedRate = 120000)
    fun evictCachesAtIntervals() {
        // Not needed but a just in case someone modifies affiliates outside of this service scope
        cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.clear() }
    }
}
