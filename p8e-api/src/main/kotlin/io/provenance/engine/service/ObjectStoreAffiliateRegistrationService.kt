package io.provenance.engine.service

import io.p8e.crypto.SignerFactory
import io.p8e.util.toHex
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toPublicKey
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Service
import p8e.Jobs
import java.lang.Exception
import java.security.PublicKey

private class ObjectStoreRegistrationException(message: String): Throwable(message)

@Service
class ObjectStoreAffiliateRegistrationService(
    private val objectStoreQueryService: ObjectStoreQueryService,
    private val affiliateService: AffiliateService,
    private val osClient: OsClient,
    private val signerFactory: SignerFactory,
): JobHandlerService, ApplicationListener<ApplicationReadyEvent> {
    companion object {
        private val OS_REGISTERED_AFFILIATES = mutableSetOf<PublicKey>()
    }

    private val log = logger()

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        transaction { affiliateService.getAll() }.forEach {
            OS_REGISTERED_AFFILIATES.add(it.encryptionPublicKey.toJavaPublicKey())
            // todo: also hydrate OS_REGISTERED_AFFILIATES with some object store endpoint on startup?
        }
    }

    override fun handle(payload: Jobs.P8eJob) {
        val request = payload.resolveAffiliateOSLocators

        val localAffiliate = request.localAffiliate.toPublicKey()
        val failedRegistrations = request.remoteAffiliatesList
            .map { it.toPublicKey() }
            .filterNot { remoteAffiliate ->
                registerRemoteAffiliate(localAffiliate, remoteAffiliate)
            }

        if (failedRegistrations.isNotEmpty()) {
            // prevent job from succeeding until all affiliates successfully registered
            throw ObjectStoreRegistrationException("Failed to register public keys with Object Store: [${failedRegistrations.joinToString { it.toHex() }}]")
        }
    }

    private fun registerRemoteAffiliate(localAffiliate: PublicKey, remoteAffiliate: PublicKey): Boolean = if (!OS_REGISTERED_AFFILIATES.contains(remoteAffiliate)) {
        try {
            objectStoreQueryService.getObjectStoreUri(remoteAffiliate, localAffiliate)
                .also {
                    osClient.createPublicKey(remoteAffiliate, it)
                    OS_REGISTERED_AFFILIATES.add(remoteAffiliate)
                }
            true
        } catch (t: Throwable) {
            log.error("Failed to register remote affiliate with object store", t)
            false
        }
    } else {
        true
    }
}
