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
import io.provenance.p8e.webservice.util.AccessDeniedException
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.security.KeyPair
import java.security.PublicKey

@Component
class AffiliateRepository(private val affiliateService: AffiliateService) {
    fun getAll(): List<ApiAffiliateKey> = transaction {
        affiliateService.getAll()
            .with(AffiliateRecord::serviceKeys)
            .map { it.toApi() }
    }

    fun create(signingKeyPair: KeyPair, encryptionKeyPair: KeyPair, indexName: String?, alias: String?): ApiAffiliateKey = transaction {
        affiliateService.save(
            signingKeyPair,
            encryptionKeyPair,
            indexName,
            alias,
        ).toApi(true)
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
