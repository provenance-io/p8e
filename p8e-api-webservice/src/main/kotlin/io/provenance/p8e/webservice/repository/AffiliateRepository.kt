package io.provenance.p8e.webservice.repository

import io.p8e.util.orThrowNotFound
import io.p8e.util.toHex
import io.provenance.p8e.shared.domain.AffiliateRecord
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.webservice.controller.ApiServiceKey
import io.provenance.p8e.webservice.controller.toApi
import io.provenance.p8e.webservice.domain.toApi
import io.provenance.p8e.webservice.domain.ApiAffiliateKey
import io.provenance.p8e.webservice.domain.ApiAffiliateShare
import io.provenance.p8e.webservice.interceptors.provenanceIdentityUuid
import io.provenance.p8e.webservice.interceptors.provenanceJwt
import io.provenance.p8e.webservice.util.AccessDeniedException
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.security.KeyPair
import java.security.PublicKey

@Component
class AffiliateRepository(private val affiliateService: AffiliateService) {
    fun getAll(): List<ApiAffiliateKey> = transaction {
        affiliateService.getAllRegistered(provenanceIdentityUuid())
            .with(AffiliateRecord::serviceKeys)
            .map { it.toApi() }
    }

    fun create(signingKeyPair: KeyPair, encryptionKeyPair: KeyPair, authKeyPair: KeyPair, indexName: String?, alias: String?): ApiAffiliateKey = transaction {
        affiliateService.save(
            signingKeyPair,
            encryptionKeyPair,
            authKeyPair.public,
            indexName,
            alias,
            provenanceJwt(),
            provenanceIdentityUuid()
        ).toApi(true)
    }

    fun update(publicKey: PublicKey, alias: String?): ApiAffiliateKey = transaction {
        checkCanManageAffiliate(publicKey) {
            AffiliateRecord.findForUpdate(publicKey)
                .orThrowNotFound("Affiliate record not found")
                .let {
                    it.alias = alias
                    it
                }.toApi()
        }
    }

    fun getShares(affiliatePublicKey: PublicKey): List<ApiAffiliateShare> = transaction {
        checkCanManageAffiliate(affiliatePublicKey) {
            affiliateService.getShares(affiliatePublicKey).map { it.toApi() }
        }
    }

    fun addShare(affiliatePublicKey: PublicKey, sharePublicKey: PublicKey): ApiAffiliateShare = transaction {
        checkCanManageAffiliate(affiliatePublicKey) {
            affiliateService.addShare(affiliatePublicKey, sharePublicKey).toApi()
        }
    }

    fun removeShare(affiliatePublicKey: PublicKey, sharePublicKey: PublicKey): Unit = transaction {
        checkCanManageAffiliate(affiliatePublicKey) {
            affiliateService.removeShare(affiliatePublicKey, sharePublicKey)
        }
    }

    fun attachServiceKeys(affiliatePublicKey: PublicKey, servicePublicKeys: List<PublicKey>): List<ApiServiceKey> = transaction {
        checkCanManageAffiliate(affiliatePublicKey) {
            affiliateService.attachServiceKeys(affiliatePublicKey, servicePublicKeys).map { it.toApi() }
        }
    }

    fun removeServiceKeys(affiliatePublicKey: PublicKey, servicePublicKeys: List<PublicKey>): Int = transaction {
        checkCanManageAffiliate(affiliatePublicKey) {
            affiliateService.removeServiceKeys(affiliatePublicKey, servicePublicKeys)
        }
    }

    private fun <T> checkCanManageAffiliate(affiliatePublicKey: PublicKey, fn: () -> T) = transaction {
        if (affiliateService.canManageAffiliate(affiliatePublicKey, provenanceIdentityUuid())) {
            fn()
        } else {
            throw AccessDeniedException("Cannot manage affiliate with public key ${affiliatePublicKey.toHex()}")
        }
    }
}
