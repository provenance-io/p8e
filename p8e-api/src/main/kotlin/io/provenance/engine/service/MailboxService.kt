package io.provenance.engine.service

import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.EnvelopeError
import io.p8e.proto.PK
import io.p8e.proto.Affiliate.PublicKeyAllowed
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toByteString
import io.p8e.util.toUuidProv
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.engine.batch.MailboxMeta
import io.provenance.engine.batch.MailboxReaper
import io.provenance.os.baseclient.client.http.ApiException
import io.provenance.os.mailbox.client.MailboxClient
import io.provenance.os.mailbox.client.iterator.DIMEInputStreamResponse
import io.provenance.p8e.shared.service.AffiliateService
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import java.nio.file.Files.readAllBytes
import java.security.KeyPair
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS

@Service
class MailboxService(
    private val affiliateService: AffiliateService,
    private val mailboxClient: MailboxClient
) {
    private val log = logger()

    private val systemKeyPair = ProvenanceKeyGenerator.generateKeyPair(curve = ECUtils.LEGACY_DIME_CURVE)

    /**
     * Poll next message from inbox for configured key-pair.
     *
     * @param [keyPair] The affiliate key pair to poll for
     * @param [limit] How many records to poll for at once
     * @return the collection of dime and decrypted bytearray for key-pair
     */
    fun poll(keyPair: KeyPair, limit: Int = 200): List<Pair<DIMEInputStreamResponse, ByteArray>> = try {
        log.debug("Polling mailbox client")

        val results = mutableListOf<Pair<DIMEInputStreamResponse, ByteArray>>()
        val signer = transaction { affiliateService.getSigner(keyPair.public) }
        val encryptionKeyRef = transaction { affiliateService.getEncryptionKeyRef(keyPair.public) }

        mailboxClient.poll(keyPair.public, limit = limit).use { iterator ->
            while (iterator.hasNext()) {
                iterator.next().let { dimeInputStreamResponse ->
                    dimeInputStreamResponse.dimeInputStream.getDecryptedPayload(encryptionKeyRef, signer).use {
                        // TODO - double check readAllBytes is safe for our use case
                        val bytes = it.readAllBytes()
                        if (!it.verify()) {
                            throw NotFoundException(
                                """
                                    Object was fetched but we're unable to verify item signature
                                    [public key: ${keyPair.public.toHex()}]
                                    [hash: ${dimeInputStreamResponse.sha512.toByteString()}]
                                """.trimIndent()
                            )
                        }
                        results.add(Pair(dimeInputStreamResponse, bytes))
                    }

                }
            }
        }

        results
    } catch (e: ApiException) {
        log.warn("Unable to poll ${keyPair.public} from mailbox client\n", e)
        emptyList()
    }

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
        mailboxClient.put(
            uuid = UUID.randomUUID(),
            message = env,
            ownerEncryptionKeyRef = encryptionKeyRef,
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

        mailboxClient.put(
            uuid = UUID.randomUUID(),
            message = error,
            ownerEncryptionKeyRef = encryptionKeyRef,
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

        mailboxClient.put(
            uuid = UUID.randomUUID(),
            message = env,
            ownerEncryptionKeyRef = encryptionKeyRef,
            signer = signer,
            additionalAudiences = additionalAudiences,
            metadata = MailboxMeta.MAILBOX_RESPONSE
        )
    }

    /**
     * Return a boolean stating whether this public key has already been registered with another p8e instance
     *
     * @param [publicKey] The public key to check if exists anywhere
     */
    fun encryptionPublicKeyExists(
        publicKey: PublicKey
    ): Boolean {
        val publicKeyBytes = ECUtils.convertPublicKeyToBytes(publicKey)
        val completableFuture = CompletableFuture<Boolean>()

        val signer = affiliateService.getSigner(publicKey)
        val encryptionKeyRef = affiliateService.getEncryptionKeyRef(publicKey)

        try {
            MailboxReaper.publicKeyCheckCallbacks[publicKeyBytes.base64encodeBytes().toString(Charsets.UTF_8)] = {
                completableFuture.complete(it)
            }
            mailboxClient.put(
                uuid = UUID.randomUUID(),
                message = PK.PublicKey
                    .newBuilder()
                    .setPublicKeyBytes(
                        publicKeyBytes
                            .toByteString()
                    ).build(),
                ownerEncryptionKeyRef = encryptionKeyRef,
                signer = signer,
                additionalAudiences = setOf(publicKey),
                metadata = MailboxMeta.MAILBOX_PUBLIC_KEY_ALLOWED
            )
        } catch (t: Throwable) {
            completableFuture.completeExceptionally(t)
        }
        return try {
            completableFuture.get(3, SECONDS)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * Return a boolean stating whether this public key has already been registered with another p8e instance
     *
     * @param [publicKeyAllow] The public key allowed proto to state if it's allowed
     */
    fun encryptionPublicKeyExistsResponse(
        ownerKeyPair: KeyPair,
        receiverPublicKey: PublicKey,
        publicKeyAllowed: PublicKeyAllowed
    ) {
        val signer = affiliateService.getSigner(ownerKeyPair.public)
        val encryptionKeyRef = affiliateService.getEncryptionKeyRef(ownerKeyPair.public)

        try {
            mailboxClient.put(
                uuid = UUID.randomUUID(),
                message = publicKeyAllowed,
                ownerEncryptionKeyRef = encryptionKeyRef,
                signer = signer,
                additionalAudiences = setOf(receiverPublicKey),
                metadata = MailboxMeta.MAILBOX_PUBLIC_KEY_ALLOWED_RESPONSE
            )
        } catch (t: Throwable) {
            logger().error("Error returning ${MailboxMeta.MAILBOX_PUBLIC_KEY_ALLOWED_RESPONSE}")
        }
    }
}
