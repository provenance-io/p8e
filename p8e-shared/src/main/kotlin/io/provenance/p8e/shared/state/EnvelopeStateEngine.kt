package io.provenance.p8e.shared.state

import io.p8e.proto.ContractScope
import io.p8e.proto.ContractScope.Envelope.Status.CREATED
import io.p8e.proto.ContractScope.Envelope.Status.CHAINCODE
import io.p8e.proto.ContractScope.Envelope.Status.COMPLETE
import io.p8e.proto.ContractScope.Envelope.Status.INDEX
import io.p8e.proto.ContractScope.Envelope.Status.SIGNED
import io.p8e.proto.ContractScope.Envelope.Status.ERROR
import io.p8e.proto.ContractScope.Envelope.Status.EXECUTED
import io.p8e.proto.ContractScope.Envelope.Status.OUTBOX
import io.p8e.proto.ContractScope.Envelope.Status.FRAGMENT
import io.p8e.proto.ContractScope.Envelope.Status.INBOX
import io.p8e.proto.ContractScope.Envelope.Status.UNRECOGNIZED
import io.p8e.proto.ContractScope.EnvelopeState
import io.p8e.proto.ContractScope.EnvelopeState.Builder
import io.p8e.proto.ContractScope.Scope
import io.p8e.util.toJavaPublicKey
import io.p8e.util.auditedProv
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toOffsetDateTimeProv
import io.p8e.util.toProtoTimestampProv
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.shared.domain.ScopeRecord
import io.provenance.p8e.shared.extension.filterByGroup
import io.provenance.p8e.shared.extension.pendingConsiderationBuilders
import io.provenance.p8e.shared.extension.record
import java.time.OffsetDateTime

class EnvelopeStateEngine {

    private val log = logger()

    /**
        CREATED = 0 [(description) = "Envelope created."];
        FRAGMENT = 1 [(description) = "Envelope sent to other parties, awaiting responses."];
        INBOX = 2 [(description) = "Envelope received."];
        EXECUTED = 3 [(description) = "Envelope executed by non-invoker."];
        OUTBOX = 4 [(description) = "Envelope response sent from non-invoker to invoker."];
        SIGNED = 5 [(description) = "Envelope is complete with all signatures."];
        CHAINCODE = 6 [(description) = "Envelope has been sent to chaincode."];
        INDEX = 7 [(description) = "Envelope has been returned from chaincode."];
        COMPLETE = 8 [(description) = "Envelope has been completed."];
        ERROR = 11 [(description) = "Envelope is in error state."];
    */

    fun onHandleCreated(record: EnvelopeRecord, state: EnvelopeState): EnvelopeRecord {
        log.info("Handling created state for envelope")

        record.isInvoker = true
        record.status = CREATED
        record.createdTime = state.auditFields.createdDate.toOffsetDateTimeProv()

        log.info("Committing envelope state data ${record.status}")
        record.data = state.toBuilder()
                .apply { inputBuilder.status = CREATED }
                .build()

        return record

    }

    fun onHandleFragment(record: EnvelopeRecord): EnvelopeRecord {
        log.info("Handling fragment state for envelope")

        val now  = OffsetDateTime.now()

        record.status = FRAGMENT
        record.fragmentTime = now
        record.data = commitEnvelopeState(record, now)
                .auditedProv()
                .build()

        return record
    }

    fun onHandleInbox(record: EnvelopeRecord, state: EnvelopeState): EnvelopeRecord {
        log.info("Handling inbox state for envelope")

        val now = OffsetDateTime.now()

        record.status = INBOX
        record.inboxTime = now

        log.info("Committing envelope state data ${record.status}")
        record.data = state.toBuilder()
                .apply { inputBuilder.status = INBOX }
                .build()

       return record
    }

    fun onHandleRead(record: EnvelopeRecord): EnvelopeRecord {
        log.info("Handling read state for envelope")

        val now = OffsetDateTime.now()

        record.readTime = now
        record.data = record.data.toBuilder()
                .setReadTime(now.toProtoTimestampProv())
                .auditedProv()
                .build()

        return record
    }

    fun onHandleExecute(record: EnvelopeRecord, result: ContractScope.Envelope): EnvelopeRecord {
        log.info("Handling execute state for envelope")

        val now = OffsetDateTime.now()

        record.status = EXECUTED
        record.executedTime = now
        record.data = commitEnvelopeState(record = record, now = now, result = result)
                .auditedProv()
                .build()

        return record
    }

    fun onHandleOutbox(record: EnvelopeRecord): EnvelopeRecord {
        log.info("Handling outbox state for envelope")

        val now = OffsetDateTime.now()

        record.status = OUTBOX
        record.outboundTime = now
        record.data = commitEnvelopeState(record, now)
                .auditedProv()
                .build()

        return record
    }

    fun onHandleSign(record: EnvelopeRecord): EnvelopeRecord {
        log.info("Handling signed state for envelope")

        val now = OffsetDateTime.now()

        if(record.errorTime != null) {
            log.info("Envelope has an error time - marking as errored.")
            return onHandleError(record)
        } else if(record.status == SIGNED && record.signedTime != null) {
            log.info("Envelope is already signed, moving to state chaincode")
            return onHandleChaincode(record)
        } else {
            log.info("Setting envelope to signed state")

            record.status = SIGNED
            record.signedTime = now
            record.data = commitEnvelopeState(record, now)
                    .auditedProv()
                    .build()

            return record
        }
    }

    fun onHandleChaincode(record: EnvelopeRecord, now: OffsetDateTime = OffsetDateTime.now()): EnvelopeRecord {
        log.info("Handling chaincode state for envelope")

        if(record.errorTime != null) {
            log.info("Envelope has an error time marking as errored.")
            return onHandleError(record)
        } else if(record.status == CHAINCODE && record.chaincodeTime != null) {
            log.info("Envelope has been ChainCoded, moving to Index state")

            val scope = ScopeRecord.findByPublicKeyAndScopeUuid(
                    record.publicKey.toJavaPublicKey(),
                    record.scope.scopeUuid)?.data?.takeIf { it.hasUuid() }

            return if(scope == null) {
                log.error("Error on Envelope - no scope record found!!")
                onHandleError(record)
            } else {
                onHandleIndex(record = record, scope = scope)
            }

        } else {
            log.info("Setting envelope to chaincode state")

            record.status = CHAINCODE
            record.chaincodeTime = now
            record.data = commitEnvelopeState(record, now)
                    .auditedProv()
                    .build()

            return record
        }
    }

    fun onHandleIndex(record: EnvelopeRecord, scope: Scope): EnvelopeRecord {
        log.info("Handling index state for envelope")

        val now = OffsetDateTime.now()

        if(record.errorTime != null) {
            log.info("Envelope has an error time - marking as errored")
            return onHandleError(record)
        } else if (record.status == INDEX && record.indexTime != null) {
            log.info("Envelope has been Index, moving to completed state")
            return onHandleComplete(record)
        } else {
            log.info("Setting envelope to index state")

            record.status = INDEX
            record.indexTime = now
            record.data = commitEnvelopeState(record = record, now = now, scope = scope)
                    .auditedProv()
                    .build()

            return record
        }
    }

    fun onHandleComplete(record: EnvelopeRecord): EnvelopeRecord {
        log.info("Handling completed state of envelope")

        val now = OffsetDateTime.now()

        if(record.errorTime != null) {
            log.info("Envelope has an error time marking as errored")
            return onHandleError(record)
        } else if(record.data.hasCompleteTime()) {
            log.warn("Cleaning up already completed envelope.")
            record.status = COMPLETE
            record.completeTime = record.data.completeTime.toOffsetDateTimeProv()
        } else {
            log.info("Setting envelope to completed state")

            record.status = COMPLETE
            record.completeTime = now
            record.data = commitEnvelopeState(record, now)
                    .auditedProv()
                    .build()
        }

        return record
    }

    fun onHandleError(record: EnvelopeRecord): EnvelopeRecord {
        log.info("Handling errored state of envelope")

        val now = OffsetDateTime.now()

        /**
         * Error time is set in DomainExtension.EnvelopeRecord.newError
         */
        record.status = ERROR
        record.data = commitEnvelopeState(record, now)
                .auditedProv()
                .build()

        return record
    }

    private fun commitEnvelopeState(record: EnvelopeRecord, now: OffsetDateTime, scope: Scope? = null, result: ContractScope.Envelope? = null): Builder {
        log.info("Committing envelope state ${record.status}")

        return when (record.status) {
            FRAGMENT ->
                record.data.toBuilder()
                        .setFragmentTime(now.toProtoTimestampProv())
                        .apply { resultBuilder.status = FRAGMENT }
            EXECUTED ->
                record.data.toBuilder()
                        .setResult(result)
                        .setExecutedTime(now.toProtoTimestampProv())
                        .apply { resultBuilder.status = EXECUTED }
            OUTBOX ->
                record.data.toBuilder()
                        .setOutboundTime(now.toProtoTimestampProv())
                        .apply { resultBuilder.status = OUTBOX }
            SIGNED ->
                record.data.toBuilder()
                        .setSignedTime(now.toProtoTimestampProv())
                        .apply { resultBuilder.status = SIGNED }
            CHAINCODE ->
                record.data.toBuilder()
                        .setChaincodeTime(now.toProtoTimestampProv())
                        .apply { resultBuilder.status = CHAINCODE }
            INDEX ->
                record.data.toBuilder()
                        .apply { resultBuilder.status = INDEX }
                        .also { builder -> builder.resultBuilder.pendingConsiderationBuilders().record(scope!!.filterByGroup(record.groupUuid)) }
                        .setIndexTime(now.toProtoTimestampProv())
                        .clearErrorTime()
            COMPLETE ->
                record.data.toBuilder()
                        .setCompleteTime(now.toProtoTimestampProv())
                        .apply { resultBuilder.status = COMPLETE }
            ERROR ->
                record.data.toBuilder()
                        .apply {
                            if (record.data.hasResult()) {
                                resultBuilder.status = ERROR
                            } else {
                                inputBuilder.status = ERROR
                            }
                        }
            CREATED, INBOX, UNRECOGNIZED -> throw IllegalStateException("Unhandled envelope status: ${record.status}") // Handled differently
        }
    }
}
