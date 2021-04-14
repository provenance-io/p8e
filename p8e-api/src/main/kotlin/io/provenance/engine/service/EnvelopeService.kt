package io.provenance.engine.service

import com.google.protobuf.Timestamp
import io.p8e.crypto.SignerImpl
import io.p8e.engine.ContractEngine
import io.p8e.proto.ContractScope.*
import io.p8e.proto.ContractScope.Envelope.Status
import io.p8e.proto.ContractScope.EnvelopeError.Type
import io.p8e.proto.Contracts.ExecutionResult.Result.FAIL
import io.p8e.proto.Envelope.EnvelopeUuidWithError
import io.p8e.proto.Events.P8eEvent
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.proto.Events.P8eEvent.Event.ENVELOPE_FRAGMENT
import io.p8e.proto.Events.P8eEvent.Event.ENVELOPE_MAILBOX_OUTBOUND
import io.p8e.proto.PK
import io.p8e.util.*
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.shared.domain.EnvelopeTable
import io.provenance.p8e.shared.domain.ScopeRecord
import io.provenance.engine.extension.*
import io.provenance.engine.grpc.v1.toEvent
import io.provenance.p8e.shared.state.EnvelopeStateEngine
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.shared.util.P8eMDC
import org.springframework.stereotype.Component
import java.security.PublicKey
import java.time.Duration
import java.time.OffsetDateTime
import java.util.*
import kotlin.also

@Component
class EnvelopeService(
    private val affiliateService: AffiliateService,
    private val osClient: OsClient,
    private val mailboxService: MailboxService,
    private val envelopeStateEngine: EnvelopeStateEngine,
    private val eventService: EventService,
    private val metricsService: MetricsService,
    private val signer: SignerImpl
) {
    private val log = logger()

    /**
     * Handle engine execution and caching of new envelopes.
     * @param [keyPair] the key this envelope belongs to
     * @param [env] the contract envelope to be executed by the Provenance Engine
     * @return the [EnvelopeRecord] db record
     */
    fun handle(publicKey: PublicKey, env: Envelope): EnvelopeRecord {
        // Don't accept any execution that is for the same publicKey and execUuid as an already existing envelope.
        require (EnvelopeRecord.findByPublicKeyAndExecutionUuidBeforeScope(publicKey, env.getExecUuid()) == null) {
            "Envelope already exists for publicKey ${publicKey.toHex()} execUuid: ${env.getExecUuid()}"
        }

        log.info("Handling envelope")

        val encryptionKeyPair = affiliateService.getEncryptionKeyPair(publicKey)
        val signingKeyPair = affiliateService.getSigningKeyPair(publicKey)

        //TODO: Move to a full UUID base key reference instead of passing sensitive key information freely.
        signer.setKeyId(signingKeyPair)

        // Update the envelope for invoker and recitals with correct signing and encryption keys.
        val envelope = env.toBuilder()
                .apply {
                    if(env.contract.startTime == Timestamp.getDefaultInstance()) {
                        contractBuilder.startTime = OffsetDateTime.now().toProtoTimestampProv()
                    }
                    contractBuilder
                        .clearInvoker()
                        .setInvoker(
                            PK.SigningAndEncryptionPublicKeys.newBuilder()
                                .setEncryptionPublicKey(affiliateService.getEncryptionKeyPair(env.contract.invoker.encryptionPublicKey.toPublicKey()).public.toPublicKeyProto())
                                .setSigningPublicKey(affiliateService.getSigningKeyPair(env.contract.invoker.signingPublicKey.toPublicKey()).public.toPublicKeyProto())
                                .build()
                        )
                        .clearRecitals()
                        .addAllRecitals(
                            env.contract.recitalsList.map {
                                it.toBuilder()
                                    .setSignerRole(it.signerRole)
                                    .setAddress(it.address)
                                    .setSigner(
                                        PK.SigningAndEncryptionPublicKeys.newBuilder()
                                            .setSigningPublicKey(affiliateService.getSigningKeyPair(it.signer.signingPublicKey.toPublicKey()).public.toPublicKeyProto())
                                            .setEncryptionPublicKey(affiliateService.getEncryptionKeyPair(it.signer.encryptionPublicKey.toPublicKey()).public.toPublicKeyProto())
                                            .build()
                                    ).build()
                            }
                        )
                }.build()

        val result = timed("EnvelopeService_contractEngine_handle") {
            ContractEngine(osClient, affiliateService).handle(
                keyPair = encryptionKeyPair,
                signingKeyPair = signingKeyPair,
                envelope = envelope,
                signer = signer
            )
        }

        return envelope.wrap(result)
            .let { state ->
                ScopeRecord.with(env.getScopeUuid(), publicKey) {
                    EnvelopeRecord.insert(this, state, publicKey, envelopeStateEngine)
                        .also { P8eMDC.set(it) }
                }
            }
            .also {
                if (envelope.hasExpirationTime())
                    it.expirationTime = env.expirationTime.toOffsetDateTimeProv()

                val errors = getErrors(it)
                if (errors.isNotEmpty()) {
                    val combined = errors.reduce { acc, s -> "[ $acc ]\n[ $s ]\n"}
                    error(publicKey, it.data.result.error(combined, Type.CONTRACT_INVOCATION))
                    return@also
                }
                if (it.data.result.isSigned()) {
                    eventService.submitEvent(
                        it.uuid.value.toProtoUuidProv().toEvent(Event.ENVELOPE_CHAINCODE),
                        it.uuid.value
                    )
                    envelopeStateEngine.onHandleSign(it)
                    return@also
                }
                log.info("Envelope will be marked as a Fragment")
                val event = P8eEvent.newBuilder()
                    .setEvent(ENVELOPE_FRAGMENT)
                    .setMessage(it.uuid.value.toProtoUuidProv().toByteString())
                    .build()
                eventService.submitEvent(
                    event,
                    it.uuid.value
                )
            }
    }

    private fun prevScope(publicKey: PublicKey, env: Envelope): Scope? {
        // TODO - determine if this is even relevant or we should always get latest scope value
        val prevExecutionUuid = env.prevExecutionUuid.toUuidOrNullProv()
        val scope = prevExecutionUuid
            ?.let { EnvelopeRecord.findByPublicKeyAndExecutionUuid(publicKey, it)!!.scopeSnapshot }
        require(prevExecutionUuid == null || scope != null) { "No scope snapshot found for prev execution:$prevExecutionUuid" }

        return scope
            ?: ScopeRecord.findByPublicKeyAndScopeUuid(publicKey, env.ref.scopeUuid.toUuidProv())?.data?.takeIf { it.hasUuid() }
    }

    /**
     * Handle envelope being staged for sdk execution.
     *
     * @param [PublicKey] public key this envelope belongs to
     * @param [env] the contract envelope to be staged
     * @return the [EnvelopeRecord] db record
     */
    fun stage(publicKey: PublicKey, env: Envelope): EnvelopeRecord {
        log.info("Staging envelope:{}, execution:{} for Public Key:{}", env.getUuid(), env.getExecUuid(), publicKey.toHex())

        // Return existing for at-least-once handling
        val record = EnvelopeRecord.findByPublicKeyAndExecutionUuid(publicKey, env.getExecUuid())
        if (record != null) {
            log.warn(
                "Cannot stage envelope:{}, execution:{} for Public Key:{} already exists",
                env.getUuid(),
                env.getExecUuid(),
                publicKey
            )
            return record
        }

        val allParties = env.contract.recitalsList.toList().plus(env.scope.partiesList)
        val signingKeyPair = affiliateService.getSigningKeyPair(publicKey)

        require(allParties.any { it.signer.signingPublicKey == publicKey.toPublicKeyProto() }) {
            "Public Key:${publicKey.toHex()} does not exist in participant list of contract:${env.ref.groupUuid.value}"
        }

        // Only store, do not process until sdk executes the contract
        return ScopeRecord.with(env.getScopeUuid(), signingKeyPair.public) {
            EnvelopeRecord.insert(this, env.stage(), signingKeyPair.public, envelopeStateEngine)
                .also { eventService.submitEvent(it.uuid.value.toProtoUuidProv().toEvent(Event.ENVELOPE_REQUEST), it.uuid.value) }
        }
    }

    /**
     * Handle engine execution for inbox'ed envelopes from other party fragmentation.
     *
     * @param [publicKey] public key making this request
     * @param [executionUuid] the contract execution uuid to be executed by the Provenance Engine
     * @return the [EnvelopeRecord] db record
     */
    fun execute(publicKey: PublicKey, executionUuid: UUID): EnvelopeRecord {
        log.info("Executing envelope")

        val record = EnvelopeRecord.findForUpdate(publicKey, executionUuid)
            ?.also { P8eMDC.set(it) }
            ?: throw NotFoundException("Envelope execution uuid:$executionUuid for Public Key:${publicKey.toHex()} not found")

        require(!record.data.isInvoker) { "Envelope uuid:${record.groupUuid} cannot be executed as invoker" }

        // Just return an already executed contract if client thinks it still needs to be run
        if (record.data.hasExecutedTime())
            return record

        val signingKeyPair = affiliateService.getSigningKeyPair(publicKey)
        val encryptionKeyPair = affiliateService.getEncryptionKeyPair(publicKey)

        //TODO: Move to a full UUID base key reference instead of passing sensitive key information freely.
        signer.setKeyId(signingKeyPair)

        timed("EnvelopeService_contractEngine_handle") {
            ContractEngine(osClient, affiliateService).handle(
                encryptionKeyPair,
                signingKeyPair,
                envelope = record.data.input,
                signer = signer
            )
        }.also { result ->
            envelopeStateEngine.onHandleExecute(record, result)

            val errors = getErrors(record)
            if (errors.isNotEmpty()) {
                val combined = errors.reduce { acc, s -> "[ $acc ]\n[ $s ]\n"}
                val error = record.data.result.error(combined, Type.CONTRACT_INVOCATION)
                error(publicKey, error)
                mailboxService.error(publicKey, record.data.result, error)
            } else {
                eventService.submitEvent(
                    record.uuid.value.toProtoUuidProv().toEvent(ENVELOPE_MAILBOX_OUTBOUND),
                    record.uuid.value
                )
            }
        }

        return record
    }

    private fun getErrors(record: EnvelopeRecord): List<String> {
        val conditionErrors = record.data
            .result
            .contract
            .conditionsList
            .filter { it.result.result == FAIL }
            .map { it.result.errorMessage }

        val considerationErrors = record.data
            .result
            .contract
            .considerationsList
            .filter { it.result.result == FAIL }
            .map { it.result.errorMessage }
        return conditionErrors + considerationErrors
    }

    /**
     * Handle envelope being marked as sdk has read the fragment.
     *
     * @param [PublicKey] the account public key making this request
     * @param [executionUuid] the contract execution uuid to be marked as read
     * @return the [EnvelopeRecord] db record
     */
    fun read(publicKey: PublicKey, executionUuid: UUID): EnvelopeRecord {
        log.info("Reading envelope:{} for Public Key:{}", executionUuid, publicKey.toHex())

        val record = EnvelopeRecord.findForUpdate(publicKey, executionUuid)
            ?: throw NotFoundException("Envelope execution uuid:$executionUuid for Public Key:${publicKey.toHex()} not found")

        require(!record.data.isInvoker) { "Envelope uuid:${record.groupUuid} cannot be read as invoker" }
        if (record.readTime != null && record.data.hasReadTime()) {
            return record
        }

        eventService.completeInProgressEvent(record.uuid.value)

        return envelopeStateEngine.onHandleRead(record)
    }

    /**
     * Handle envelope being marked as error read for a specific error.
     *
     * @param [PublicKey] the account public key making this request
     * @param [executionUuid] the contract execution uuid to be marked as read
     * @param [errorUuid] the error uuid to be marked as read
     * @return the [EnvelopeRecord] db record
     */
    fun read(publicKey: PublicKey, executionUuid: UUID, errorUuid: UUID): EnvelopeRecord {
        log.info("Reading error:{} envelope:{} for Public Key:{}", errorUuid, executionUuid, publicKey.toHex())

        val record = EnvelopeRecord.findForUpdate(publicKey, executionUuid)
            ?: throw NotFoundException("Envelope execution uuid:$executionUuid for Public Key:${publicKey.toHex()} not found")

        record.data = record.data.toBuilder()
            .also {
                it.errorsBuilderList
                    .firstOrNull { builder -> builder.uuid == errorUuid.toProtoUuidProv() }
                    ?.run {
                        if (!hasReadTime()) {
                            val now = OffsetDateTime.now()
                            readTime = now.toProtoTimestampProv()
                        }
                        auditedProv()
                    }
            }
            .build()
        eventService.completeInProgressEvent(record.uuid.value)

        return record
    }

    /**
     * Handle envelope being marked as sdk has read the index and is complete.
     *
     * @param [PublicKey] the account public key making this request
     * @param [executionUuid] the contract execution uuid to be marked as read
     * @return the [EnvelopeRecord] db record
     */
    fun complete(publicKey: PublicKey, executionUuid: UUID): EnvelopeRecord {
        log.info("Completing execution:{} for Public Key:{}", executionUuid, publicKey.toHex())

        val record = EnvelopeRecord.findForUpdate(publicKey, executionUuid)
            ?: throw NotFoundException("Envelope execution uuid:$executionUuid for Public Key:${publicKey.toHex()} not found")

        envelopeStateEngine.onHandleComplete(record)
        metricsService.logEnvelopeStats(record)

        eventService.completeInProgressEvent(record.uuid.value)

        return record
    }

    fun MetricsService.logEnvelopeStats(envelope: EnvelopeRecord) {
        val labels = mapOf("envelope_status" to if (envelope.status == Status.COMPLETE) "success" else "error")
        logEnvelopeTimeSlice("envelope_signed", envelope.createdTime, envelope.signedTime, labels)
        logEnvelopeTimeSlice("envelope_chaincode", envelope.signedTime, envelope.chaincodeTime, labels)
        logEnvelopeTimeSlice("envelope_index", envelope.chaincodeTime, envelope.indexTime, labels)
        logEnvelopeTimeSlice("envelope_complete", envelope.indexTime, envelope.completeTime, labels)
        logEnvelopeTimeSlice("envelope_total", envelope.createdTime, envelope.completeTime, labels)
    }

    fun MetricsService.logEnvelopeTimeSlice(aspect: String, startTime: OffsetDateTime?, endTime: OffsetDateTime?, labels: Map<String, String>) {
        if (startTime != null && endTime != null) {
            time(aspect, Duration.between(startTime, endTime), labels)
        }
    }

    /**
     * Handle envelope merging from other party executions. If envelope is fully signed, mark as such for chaincode execution.
     *
     * @param [PublicKey] the account public key making this request
     * @param [env] the contract envelope to be merged
     * @return the [EnvelopeRecord] db record
     */
    fun merge(publicKey: PublicKey, env: Envelope): EnvelopeRecord {
        log.info("Merging envelope:{} for Public Key:{}", env.ref.groupUuid.value, publicKey.toHex())

        val signingPublicKey = affiliateService.getSigningKeyPair(publicKey)

        val record = EnvelopeRecord.findForUpdate(signingPublicKey.public, env.getExecUuid())

        require(record != null) { "Envelope not found uuid:${env.getExecUuid()}" }
        require(record.data.hasResult()) { "Envelope must have result uuid:${env.getExecUuid()}" }
        require(env.contract == record.data.result.contract) { "Executed contracts do not match uuid:${env.getExecUuid()}" }
        require(env.signaturesCount > 0) { "Executed contract does not contain signature uuid:${env.getExecUuid()}"}

        // Return existing for at-least-once handling
        if (record.data.result.hasSignature(env.signaturesList.first())) {
            log.warn(
                "Cannot merge envelope:{}, execution:{} for Public Key:{}, signature:{} already exists",
                env.getUuid(),
                env.getExecUuid(),
                publicKey.toHex(),
                env.signaturesList.first().signer.signingPublicKey.toHex()
            )
            return record
        }

        record.data = record.data.toBuilder()
            .also { builder ->
                builder.result = builder.resultBuilder.addSignature(env).build()

                if (builder.result.isSigned()) {
                    // Update the status to signed so that it is picked up by the memorialization function.
                    if (record.status == Status.FRAGMENT) {
                       envelopeStateEngine.onHandleSign(record)
                    }

                    builder.signedTime = OffsetDateTime.now().toProtoTimestampProv()
                    eventService.submitEvent(
                        record.uuid.value.toProtoUuidProv().toEvent(Event.ENVELOPE_CHAINCODE),
                        record.uuid.value
                    )
                }
            }
            .auditedProv()
            .build()

        return record
    }

    /**
     * Handle indexer callback by updating record and scope as complete.
     *
     * @param [EnvelopeRecord] the envelope to update
     * @param [result] the Scope result from indexer
     */
    fun index(record: EnvelopeRecord, result: Scope, transactionHash: String, blockHeight: Long) {
        log.info(
            "Processing indexing group:{}, execution:{}, id:{}",
            result.lastEvent.groupUuid.value,
            result.lastEvent.executionUuid.value,
            record.uuid.value
        )

        if (!result.hasLastEvent() || !result.lastEvent.hasExecutionUuid() || !result.lastEvent.hasGroupUuid()) {
            log.error("No last event found for record:{}", result.uuid.value)
            return
        }

        if (record.data.hasIndexTime())
            return
        if (record.groupUuid != result.lastEvent.groupUuid.toUuidProv()) {
            log.error("Group ids do not match record:{}, index:{}", record.groupUuid, result.lastEvent.groupUuid.value)
            return
        }

        record.transactionHash = transactionHash
        record.blockHeight = blockHeight

        envelopeStateEngine.onHandleIndex(record, result)

        eventService.submitEvent(
            record.uuid.value.toProtoUuidProv().toEvent(Event.ENVELOPE_RESPONSE),
            record.uuid.value
        )

        val scope = ScopeRecord.findForUpdate(record.scope.id.value)!!

        val lastExecutionTime = scope.lastExecutionUuid
            ?.run { EnvelopeRecord.findByPublicKeyAndExecutionUuid(record.publicKey.toJavaPublicKey(), this) }
            ?.data?.chaincodeTime?.toOffsetDateTimeProv()
            ?: OffsetDateTime.MIN

        if (result.recordGroupList != scope.data.recordGroupList && lastExecutionTime < record.data.chaincodeTime.toOffsetDateTimeProv()) {
            log.info("Merging scope:{} for public key:{}", scope.uuid.value, scope.publicKey)
            scope.data = result
            scope.lastExecutionUuid = record.executionUuid
        }
    }

    /**
     * Handle envelope add error and setting errored.
     *
     * @param [PublicKey] the account public key of envelope
     * @param [error] the EnvelopeError result to add to envelope
     * @return the [EnvelopeRecord] db record
     */
    fun error(publicKey: PublicKey, error: EnvelopeError): EnvelopeRecord? {
        log.info(
            "Processing error group:{}, execution:{}, Public Key:{}",
            error.groupUuid.value,
            error.executionUuid.value,
            publicKey.toHex()
        )

        // XXX - We need to verify that the **ENCRYPTION** publicKey
        // matches a participant of the contract for errors received via mailbox..
        return EnvelopeRecord.findForUpdate(publicKey, error.executionUuid.toUuidProv())
            ?.takeIf { it.data.errorsList.none { e -> e.uuid == error.uuid } }
            ?.also { it.data = it.newError(error) }
            ?.also {
                envelopeStateEngine.onHandleError(it)
                metricsService.logEnvelopeStats(it)
                val envWithError = EnvelopeUuidWithError.newBuilder()
                    .setError(error)
                    .setEnvelopeUuid(it.uuid.value.toProtoUuidProv())
                    .build()
                eventService.submitEvent(envWithError.toEvent(Event.ENVELOPE_ERROR), it.uuid.value)
            }
            ?: run {
                log.warn(
                    "Cannot error envelope:{}, execution:{} for public key:{} not found or already handled",
                    error.groupUuid.value,
                    error.executionUuid.value,
                    publicKey
                )
                null
            }
    }
}
