package io.provenance.engine.service

import io.p8e.crypto.SignerFactory
import io.p8e.util.toHex
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toPublicKey
import io.provenance.engine.util.SigningAndEncryptionPublicKeys
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Service
import p8e.Jobs
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
            OS_REGISTERED_AFFILIATES.add(it.publicKey.value.toJavaPublicKey())
            // todo: also hydrate OS_REGISTERED_AFFILIATES with some object store endpoint on startup?
        }
    }

    override fun handle(payload: Jobs.P8eJob) {
        val request = payload.resolveAffiliateOSLocators

        val localAffiliateSigningPublicKey = request.localAffiliate.toPublicKey()
        val failedRegistrations = request.remoteAffiliatesList
            .map { SigningAndEncryptionPublicKeys(it.signingPublicKey.toPublicKey(), it.encryptionPublicKey.toPublicKey()) }
            .filterNot { remoteAffiliatePublicKeys ->
                registerRemoteAffiliate(localAffiliateSigningPublicKey, remoteAffiliatePublicKeys)
            }

        if (failedRegistrations.isNotEmpty()) {
            // prevent job from succeeding until all affiliates successfully registered
            throw ObjectStoreRegistrationException("Failed to register public keys with Object Store: [${failedRegistrations.joinToString { "(signing = ${it.signing.toHex()}, encryption = ${it.encryption.toHex()})" }}]")
        }
    }

    private fun registerRemoteAffiliate(localAffiliateSigningPublicKey: PublicKey, remoteAffiliatePublicKeys: SigningAndEncryptionPublicKeys): Boolean = if (!OS_REGISTERED_AFFILIATES.contains(remoteAffiliatePublicKeys.signing)) {
        try {
            objectStoreQueryService.getObjectStoreDetails(remoteAffiliatePublicKeys.signing, localAffiliateSigningPublicKey)
                .also {
                    osClient.createPublicKey(remoteAffiliatePublicKeys.signing, remoteAffiliatePublicKeys.encryption, it)
                    OS_REGISTERED_AFFILIATES.add(remoteAffiliatePublicKeys.signing)
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
