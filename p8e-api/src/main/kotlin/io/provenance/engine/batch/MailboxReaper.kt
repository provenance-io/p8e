package io.provenance.engine.batch

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.Message
import io.p8e.proto.Affiliate.PublicKeyAllowed
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.EnvelopeError
import io.p8e.proto.ContractScope.EnvelopeError.Type
import io.p8e.proto.ContractScope.EnvelopeState
import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.proto.PK
import io.p8e.util.*
import io.p8e.util.auditedProv
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.randomProtoUuidProv
import io.p8e.util.toByteString
import io.p8e.util.toProtoUuidProv
import io.p8e.util.toUuidOrNullProv
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.engine.config.ReaperInboxProperties
import io.provenance.engine.config.ReaperOutboxProperties
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.engine.domain.EventStatus
import io.provenance.engine.domain.toUuid
import io.provenance.engine.extension.error
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.engine.service.EnvelopeService
import io.provenance.engine.service.EventService
import io.provenance.engine.service.MailboxService
import io.provenance.p8e.shared.state.EnvelopeStateEngine
import io.provenance.os.mailbox.client.iterator.DIMEInputStreamResponse
import io.provenance.proto.encryption.EncryptionProtos.Audience
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.security.KeyPair
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

object MailboxMeta {
    const val KEY = "P8EAPI::TYPE"
    const val FRAGMENT_REQUEST = "FRAGMENT_REQUEST"
    const val FRAGMENT_RESPONSE = "FRAGMENT_RESPONSE"
    const val ERROR_RESPONSE = "ERROR_RESPONSE"
    const val PUBLIC_KEY_ALLOWED = "PUBLIC_KEY_ALLOWED"
    const val PUBLIC_KEY_ALLOWED_RESPONSE = "PUBLIC_KEY_ALLOWED_RESPONSE"
    const val CLASSNAME_KEY = "P8EAPI::CLASSNAME"
    const val EXECUTION_KEY = "P8EAPI::EXECUTION"

    val MAILBOX_REQUEST = mapOf(KEY to FRAGMENT_REQUEST)
    val MAILBOX_RESPONSE = mapOf(KEY to FRAGMENT_RESPONSE)
    val MAILBOX_ERROR = mapOf(KEY to ERROR_RESPONSE)
    val MAILBOX_PUBLIC_KEY_ALLOWED = mapOf(KEY to PUBLIC_KEY_ALLOWED)
    val MAILBOX_PUBLIC_KEY_ALLOWED_RESPONSE = mapOf(KEY to PUBLIC_KEY_ALLOWED_RESPONSE)
}

@Component
class MailboxReaper(
    private val affiliateService: AffiliateService,
    private val envelopeService: EnvelopeService,
    eventService: EventService,
    private val mailboxService: MailboxService,
    private val envelopeStateEngine: EnvelopeStateEngine,
    reaperInboxProperties: ReaperInboxProperties,
    reaperOutboxProperties: ReaperOutboxProperties
) {

    private val log = logger()
    private val inboxExec: ExecutorService = reaperInboxProperties.toThreadPool()

    init {
        eventService.registerCallback(Event.ENVELOPE_MAILBOX_OUTBOUND, this::handleOutbound)
    }

    companion object {
        val publicKeyCheckCallbacks = ConcurrentHashMap<String, (Boolean) -> Unit>()
    }

    private class MailboxInboundCallable(
        private val affiliateService: AffiliateService,
        private val keyPair: KeyPair,
        private val envelopeService: EnvelopeService,
        private val mailboxService: MailboxService,
        private val envelopeStateEngine: EnvelopeStateEngine,
        private val resultPair: Pair<DIMEInputStreamResponse, ByteArray>
    ) : Callable<Message> {

        private val log = logger()
        private val defaultProto = Any.getDefaultInstance()
        private val publicKey = keyPair.public

        private fun ByteString.toPublicKey() = ECUtils.convertBytesToPublicKey(Base64.getDecoder().decode(toByteArray()))

        override fun call(): Message {
            val (dimeInputStreamResponse, msg) = resultPair
            val dimeInputStream = dimeInputStreamResponse.dimeInputStream

            log.info("Received mail from poll:{}", dimeInputStream.uuid)

            if (!dimeInputStream.metadata.containsKey(MailboxMeta.KEY)) {
                dimeInputStreamResponse.ack().also { log.warn("Unhandled mailbox meta:{}", dimeInputStream.metadata) }
                return defaultProto
            }

            val mailboxKey = dimeInputStream.metadata[MailboxMeta.KEY]
            val uuid = dimeInputStream.uuid

            return try {
                when (mailboxKey) {
                    MailboxMeta.FRAGMENT_REQUEST, MailboxMeta.FRAGMENT_RESPONSE -> envelope(uuid, mailboxKey, dimeInputStream.dime.owner, msg)
                    MailboxMeta.ERROR_RESPONSE -> error(uuid, dimeInputStream.dime.owner, msg)
                    MailboxMeta.PUBLIC_KEY_ALLOWED -> transaction {
                            affiliateService.getEncryptionKeyPairs()
                        }.flatMap {
                            listOf(it)
                        }.find { keyPair ->
                            PK.PublicKey.parseFrom(msg).toPublicKey() == keyPair.value.public
                        }?.let { keyPair ->
                            PublicKeyAllowed.newBuilder()
                                .setAllowed(false)
                                .setPublicKeyBytes(msg.toByteString())
                                .build() to keyPair
                        }?.also { (publicKeyAllowed, keyPair) ->
                            mailboxService.encryptionPublicKeyExistsResponse(
                                keyPair.value,
                                dimeInputStream.dime.owner.publicKey.toPublicKey(),
                                publicKeyAllowed
                            )
                        }?.first ?: PublicKeyAllowed.getDefaultInstance()
                    MailboxMeta.PUBLIC_KEY_ALLOWED_RESPONSE -> PublicKeyAllowed.parseFrom(msg)
                        .also {
                            val base64PublicKeyBytes = it.publicKeyBytes.toByteArray().toString(Charsets.UTF_8)
                            try {
                                publicKeyCheckCallbacks[base64PublicKeyBytes]
                                    ?.let { callback -> callback(it.allowed) }
                            } catch (t: Throwable) {
                                logger().error("Error handling callback for public key allowed check.", t)
                            } finally {
                                publicKeyCheckCallbacks.remove(base64PublicKeyBytes)
                            }

                        }
                    else -> throw IllegalStateException("Unhandled mailbox key: $mailboxKey")
                }
            } catch(t: Throwable) {
                when (mailboxKey) {
                    MailboxMeta.FRAGMENT_REQUEST, MailboxMeta.FRAGMENT_RESPONSE -> Envelope.parseFrom(msg).error(t.toMessageWithStackTrace(), Type.CONTRACT_INVOCATION)
                    MailboxMeta.ERROR_RESPONSE -> EnvelopeError.parseFrom(msg).let {
                        EnvelopeError.newBuilder()
                            .setUuid(randomProtoUuidProv())
                            .setExecutionUuid(it.executionUuid)
                            .setGroupUuid(it.groupUuid)
                            .setMessage(t.toMessageWithStackTrace())
                            .setType(Type.CONTRACT_INVOCATION)
                            .auditedProv()
                            .build()
                    }
                    MailboxMeta.PUBLIC_KEY_ALLOWED, MailboxMeta.PUBLIC_KEY_ALLOWED_RESPONSE -> {
                        val sender = PK.PublicKey.parseFrom(msg)
                            .publicKeyBytes
                            .toByteArray()
                        EnvelopeError.newBuilder()
                            .setUuid(randomProtoUuidProv())
                            .setExecutionUuid(UUID(0, 0).toProtoUuidProv())
                            .setGroupUuid(UUID(0, 0).toProtoUuidProv())
                            .setMessage("Issue validating use of public key ${sender.toString(Charsets.UTF_8)} ${t.toMessageWithStackTrace()}")
                            .setType(Type.PUBLIC_KEY_CHECK)
                            .auditedProv()
                            .build()
                    }
                    else -> throw IllegalStateException("Unhandled mailbox key: $mailboxKey")
                }.let { error(uuid, dimeInputStream.dime.owner, it.toByteArray())}
            } finally {
                dimeInputStreamResponse.ack()
            }
        }

        private fun envelope(uuid: UUID, mailboxKey: String, ownerAudience: Audience, msg: ByteArray): Message {
            val env = Envelope.parseFrom(msg)

            require(env.getUuidNullable() != null) { "Group uuid is required" }
            require(env.getScopeUuidNullable() != null) { "Scope uuid is required" }
            require(env.getExecUuidNullable() != null) { "Execution uuid is required" }

            val className = env.contract.definition.resourceLocation.classname

            val signingPublicKey = env.contract.recitalsList.plus(env.scope.partiesList)
                .firstOrNull { it.signer.encryptionPublicKey.toPublicKey() == publicKey }
                .orThrow { IllegalStateException("Can't find party on contract execution ${env.executionUuid.value} with key ${publicKey.toHex()}") }
                .signer
                .signingPublicKey
                .toPublicKey()

            log.info("Processing envelope mail from key:{} poll:{}, classname:{}", mailboxKey, uuid, className)

            return transaction {
                className
                    .takeIf { clazz -> affiliateService.getWhitelistContractByClassNameActive(signingPublicKey, clazz) != null }
                    ?.let {
                        // removed lock as we currently run only on a single node, might have to be re-thought through
                        // if we provide support for multi-node
//                        globalLock("mailbox-inbound-${env.executionUuid.value}") {
                            when (mailboxKey) {
                                // All fragment requests are staged for sdk action
                                MailboxMeta.FRAGMENT_REQUEST -> envelopeService.stage(signingPublicKey, env).data
                                // All executed responses from fragment are merged before executed on chain
                                MailboxMeta.FRAGMENT_RESPONSE -> envelopeService.merge(signingPublicKey, env).data
                                else -> throw IllegalStateException("Should not happen, unhandled mailbox key:$mailboxKey")
                            }
//                        }
                    }
                    ?: env
                        .error(
                            "Fragment for class:$className not whitelisted for public key:$publicKey",
                            EnvelopeError.Type.CONTRACT_WHITELIST
                        )
                        // TODO - do we need to handle only-once processing for mailing errors?
                        .also {
                            mailboxService.error(publicKey, listOf(ownerAudience.publicKey.toPublicKey()), it)
                        }
            }
        }

        private fun error(uuid: UUID, ownerAudience: Audience, msg: ByteArray): Message {
            val error = EnvelopeError.parseFrom(msg)

            require(error.groupUuid.toUuidOrNullProv() != null) { "Group uuid is required" }
            require(error.executionUuid.toUuidOrNullProv() != null) { "Execution uuid is required" }

            log.info("Processing mail error from key:{} poll:{}, message:{}", MailboxMeta.ERROR_RESPONSE, uuid, error.message)

            return transaction {
                envelopeService.error(publicKey, error)
            }?.also {
                // Send errors to parties on recital list if invoker sans the sender of the original message
                if (it.data.isInvoker)
                    mailboxService.error(
                        publicKey,
                        it.data.input,
                        error,
                        ownerAudience.publicKey.toPublicKey()
                    )
            }?.data
                ?.result
                ?: defaultProto
        }
    }

    /**
     * Handles polling mailbox for new fragment requests and process accordingly.
     */
//    @Scheduled(initialDelayString = "\${reaper.inbox.delay}", fixedDelayString = "\${reaper.inbox.interval}")
    fun pollInbound() {
        log.debug("Polling mailbox reaper inbound")

        transaction { affiliateService.getEncryptionKeyPairs() }
            .flatMap {
                    keyPair -> mailboxService.poll(keyPair.value).map { keyPair to it }
            }.map { (keys, resultPair) ->
                MailboxInboundCallable(
                    affiliateService,
                    keys.value,
                    envelopeService,
                    mailboxService,
                    envelopeStateEngine,
                    resultPair
                )
            }
            .let{ inboxExec.invokeAll(it) }
            .run { awaitFutures(MailboxInboundCallable::class) }
    }

    private val outboxExec = reaperOutboxProperties.toThreadPool()

    private class MailboxOutboundCallable(
        private val envelopeUuid: UUID,
        private val mailboxService: MailboxService,
        private val envelopeStateEngine: EnvelopeStateEngine
    ) : Callable<EnvelopeState> {

        private val log = logger()

        override fun call(): EnvelopeState =
            transaction {
                log.info("Processing outbound mail envelope:{}", envelopeUuid)

                val record = EnvelopeRecord.findForUpdate(envelopeUuid)!!

                // Lock check is for mult-node support only
                if (record.data.hasOutboundTime())
                    return@transaction record.data

                envelopeStateEngine.onHandleOutbox(record)

                // Send back to invoker
                mailboxService.result(record.scope.publicKey.toJavaPublicKey(), record.data.result)
                record.data
            }.also { log.info("Completed outbound mail envelope:{}", envelopeUuid) }
    }

    /**
     * Handles envelopes executed but not yet sent outbound.
     */
    private fun handleOutbound(event: P8eEvent): EventStatus {
        if (event.event != Event.ENVELOPE_MAILBOX_OUTBOUND) {
            throw IllegalStateException("Received table event type ${event.event} in mailbox outbound handler.")
        }

        val envelopeUuid = event.toUuid()

        return try {
            MailboxOutboundCallable(envelopeUuid, mailboxService, envelopeStateEngine)
                .let { outboxExec.submit(it) }
                .get()
            EventStatus.COMPLETE
        } catch (t: Throwable) {
            log.error("Error processing outbound for envelope $envelopeUuid", t)
            EventStatus.ERROR
        }
    }
}
