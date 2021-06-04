package io.provenance.engine.service

import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.EnvelopeError
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toUuidProv
import io.provenance.engine.batch.MailboxMeta
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.service.AffiliateService
import org.springframework.stereotype.Service
import java.security.PublicKey
import java.util.UUID

@Service
class MailboxService(
    private val affiliateService: AffiliateService,
    private val osClient: OsClient,
) {
    private val log = logger()

    // private val systemKeyPair = ProvenanceKeyGenerator.generateKeyPair(curve = ECUtils.LEGACY_DIME_CURVE)

    /**
     * Fragment an envelope by sending inputs to additional signers.
     *
     * @param [PublicKey] The owner of the message
     * @param [env] The envelope to fragment
     */
    fun fragment(publicKey: PublicKey, env: Envelope) {
        log.info("Fragmenting env:{}", env.getUuid())

        val signer = affiliateService.getSigner(publicKey)
        val encryptionKeyRef = affiliateService.getEncryptionKeyRef(publicKey)

        val invokerPublicKey = env.contract.invoker.encryptionPublicKey.toPublicKey()

        // if the invoker public key does not match the encryption or signing public key, then error.
        if (invokerPublicKey != encryptionKeyRef.publicKey && invokerPublicKey != signer.getPublicKey()) {
            log.error("Invoker publicKey: ${invokerPublicKey.toHex()} does not match application public key: ${encryptionKeyRef.publicKey.toHex()}")
            return
        }

        val scopeOwners = env.scope.partiesList
            .filter { it.hasSigner() }
            .map { it.signer.encryptionPublicKey.toPublicKey() }
            .toSet()

        val additionalAudiences = env.contract.recitalsList
            .filter { it.hasSigner() }
            // Even though owner is in the recital list, mailbox client will ignore/filter for you
            .map { it.signer.encryptionPublicKey.toPublicKey() }
            .toSet()
            .plus(scopeOwners)

        // Mail the actual contract
        osClient.put(
            uuid = UUID.randomUUID(),
            message = env,
            encryptionKeyRef = encryptionKeyRef,
            signer = signer,
            additionalAudiences = additionalAudiences,
            metadata = MailboxMeta.MAILBOX_REQUEST
        )
    }

    /**
     * Send envelope error to audiences of an envelope.
     *
     * @param [PublicKey] The owner of the message
     * @param [env] The envelope to error on
     * @param [error] The envelope error to return
     * @param [audiencePublicKeyFilter] Audiences to filter out by
     */
    fun error(publicKey: PublicKey, env: Envelope, error: EnvelopeError, vararg audiencePublicKeyFilter: PublicKey) {
        env.contract.recitalsList
            // Even though owner is in the recital list, mailbox client will ignore/filter for you
            .map { it.signer.encryptionPublicKey.toPublicKey() }
            .filterNot { audiencePublicKeyFilter.contains(it) }
            .toSet()
            .run { error(publicKey, this, error) }
    }

    /**
     * Send envelope error to audience(s).
     *
     * @param [PublicKey] The owner of the message
     * @param [audiencesPublicKey] Public key(s) to send mail to
     * @param [error] The envelope error to return
     */
    fun error(publicKey: PublicKey, audiencesPublicKey: Collection<PublicKey>, error: EnvelopeError) {
        log.info("Sending error result env:{}, error type:{}", error.groupUuid.toUuidProv(), error.type.name)

        val signer = affiliateService.getSigner(publicKey)
        val encryptionKeyRef = affiliateService.getEncryptionKeyRef(publicKey)

        osClient.put(
            uuid = UUID.randomUUID(),
            message = error,
            encryptionKeyRef = encryptionKeyRef,
            signer = signer,
            additionalAudiences = audiencesPublicKey.toSet(),
            metadata = MailboxMeta.MAILBOX_ERROR
        )
    }

    /**
     * Return the executed fragment back to originator of fragment.
     *
     * @param [PublicKey] The owner of the message
     * @param [env] The executed envelope to return
     */
    fun result(publicKey: PublicKey, env: Envelope) {
        log.info("Returning fragment result env:{}", env.getUuid())

        val additionalAudiences = setOf(env.contract.invoker.encryptionPublicKey.toPublicKey())
        val signer = affiliateService.getSigner(publicKey)
        val encryptionKeyRef = affiliateService.getEncryptionKeyRef(publicKey)

        osClient.put(
            uuid = UUID.randomUUID(),
            message = env,
            encryptionKeyRef = encryptionKeyRef,
            signer = signer,
            additionalAudiences = additionalAudiences,
            metadata = MailboxMeta.MAILBOX_RESPONSE
        )
    }
}
