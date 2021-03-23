package io.provenance.engine.service

import io.p8e.proto.Envelope
import io.p8e.proto.Events
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toProtoUuidProv
import io.provenance.engine.domain.TransactionStatus
import io.provenance.engine.domain.TransactionStatusRecord
import io.provenance.engine.extension.addChaincodeError
import io.provenance.engine.grpc.v1.toEvent
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.shared.domain.EnvelopeTable
import io.provenance.p8e.shared.state.EnvelopeStateEngine
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import io.p8e.proto.ContractScope.Envelope.Status
import io.p8e.proto.Events.P8eEvent.Event
import org.springframework.stereotype.Component
import java.util.*

@Component
class TransactionStatusService(
    private val envelopeStateEngine: EnvelopeStateEngine,
    private val mailboxService: MailboxService,
    private val eventService: EventService
) {
    fun setError(transactionStatus: TransactionStatusRecord, error: String, executionUuids: List<UUID> = transactionStatus.executionUuids) {
        transactionStatus.status = TransactionStatus.ERROR
        transactionStatus.rawLog = error

        setEnvelopeErrors(error, executionUuids)
    }

    fun setEnvelopeErrors(error: String, executionUuids: List<UUID>) {
        EnvelopeRecord.find {
            EnvelopeTable.executionUuid inList executionUuids
        }.forUpdate().forEach {envelope ->
            envelopeStateEngine.onHandleError(envelope)
            envelope.addChaincodeError(error).also {
                val envWithError = Envelope.EnvelopeUuidWithError.newBuilder()
                    .setError(it)
                    .setEnvelopeUuid(envelope.uuid.value.toProtoUuidProv())
                    .build()
                eventService.submitEvent(envWithError.toEvent(Events.P8eEvent.Event.ENVELOPE_ERROR), envelope.uuid.value) // dump for error stream

                // Ship error info to an relevant additional parties
                mailboxService.error(
                    envelope.scope.publicKey.toJavaPublicKey(),
                    envelope.data.input,
                    it
                )
            }
        }
    }

    fun retryDead(transactionStatus: TransactionStatusRecord, message: String?) {
        transactionStatus.status = TransactionStatus.ERROR
        transactionStatus.rawLog = message

        EnvelopeRecord.find {
            EnvelopeTable.executionUuid inList transactionStatus.executionUuids
        }.forUpdate().forEach { envelope ->
            envelope.status = Status.SIGNED
            envelope.chaincodeTime = null

            if (envelope.isInvoker ?: false) {
                val event = envelope.uuid.value.toProtoUuidProv().toEvent(Event.ENVELOPE_CHAINCODE)
                eventService.submitEvent(event, envelope.uuid.value)
            }
        }
    }
}
