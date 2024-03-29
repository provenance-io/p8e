package io.provenance.p8e.webservice.repository

import io.p8e.util.orThrowNotFound
import io.p8e.util.toHex
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.p8e.encryption.model.ExternalKeyRef
import io.provenance.p8e.shared.domain.AffiliateRecord
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.webservice.controller.ApiServiceKey
import io.provenance.p8e.webservice.controller.toApi
import io.provenance.p8e.webservice.domain.toApi
import io.provenance.p8e.webservice.domain.ApiAffiliateKey
import io.provenance.p8e.webservice.domain.ApiAffiliateShare
import io.provenance.p8e.webservice.service.KeyManagementService
import io.provenance.p8e.webservice.service.KeyUsageType
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.security.KeyPair
import java.security.PublicKey

@Component
class AffiliateRepository(
    private val affiliateService: AffiliateService,
    private val keyManagementService: KeyManagementService,
) {
    private val log = logger()

    fun getAll(): List<ApiAffiliateKey> = transaction {
        affiliateService.getAll()
            .with(AffiliateRecord::serviceKeys)
            .map { it.toApi() }
    }

    fun create(signingKeyPair: KeyPair, encryptionKeyPair: KeyPair, indexName: String?, alias: String?): ApiAffiliateKey = ProvenanceKeyGenerator.generateKeyPair()
        .let { authKeyPair ->
            transaction {
                affiliateService.save(
                    signingKeyPair,
                    encryptionKeyPair,
                    authKeyPair.public,
                    indexName,
                    alias
                ).toApi(authKeyPair.private.toHex())
            }
        }

    fun create(indexName: String?, alias: String?): ApiAffiliateKey {
        var signingKey: ExternalKeyRef? = null
        var encryptionKey: ExternalKeyRef? = null

        return try {
            signingKey = keyManagementService.generateKey("$alias signing key", KeyUsageType.SIGNING)
            encryptionKey = keyManagementService.generateKey("$alias encryption key", KeyUsageType.ENCRYPTION)
            val authKeyPair = ProvenanceKeyGenerator.generateKeyPair()

            transaction {
                affiliateService.save(signingKey, encryptionKey, authKeyPair.public, indexName, alias)
                    .toApi(authKeyPair.private.toHex())
            }
        } catch (t: Throwable) {
            log.error("Error creating affiliate using SmartKey provider", t)
            cleanupExternalKeyRef(signingKey)
            cleanupExternalKeyRef(encryptionKey)
            throw t;
        }
    }

    private fun cleanupExternalKeyRef(ref: ExternalKeyRef?) = try {
        ref?.uuid?.let {
            log.info("cleaning up external key ref $it")
            keyManagementService.deleteKey(it)
        }
    } catch (t: Throwable) {
        log.error("Error cleaning up external key ref ${ref?.uuid}", t)
    }

    fun update(publicKey: PublicKey, alias: String?): ApiAffiliateKey = transaction {
        AffiliateRecord.findForUpdate(publicKey)
            .orThrowNotFound("Affiliate record not found")
            .let {
                it.alias = alias
                it
            }.toApi()
    }

    fun getShares(affiliatePublicKey: PublicKey): List<ApiAffiliateShare> = transaction {
        affiliateService.getShares(affiliatePublicKey).map { it.toApi() }
    }

    fun addShare(affiliatePublicKey: PublicKey, sharePublicKey: PublicKey): ApiAffiliateShare = transaction {
        affiliateService.addShare(affiliatePublicKey, sharePublicKey).toApi()
    }

    fun removeShare(affiliatePublicKey: PublicKey, sharePublicKey: PublicKey): Unit = transaction {
        affiliateService.removeShare(affiliatePublicKey, sharePublicKey)
    }

    fun attachServiceKeys(affiliatePublicKey: PublicKey, servicePublicKeys: List<PublicKey>): List<ApiServiceKey> = transaction {
        affiliateService.attachServiceKeys(affiliatePublicKey, servicePublicKeys).map { it.toApi() }
    }

    fun removeServiceKeys(affiliatePublicKey: PublicKey, servicePublicKeys: List<PublicKey>): Int = transaction {
        affiliateService.removeServiceKeys(affiliatePublicKey, servicePublicKeys)
    }
}
