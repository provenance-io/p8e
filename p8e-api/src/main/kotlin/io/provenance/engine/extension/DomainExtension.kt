package io.provenance.engine.extension

import io.p8e.proto.ContractScope.EnvelopeError
import io.p8e.proto.ContractScope.EnvelopeState
import io.p8e.util.auditedProv
import io.p8e.util.toInstantProv
import io.p8e.util.toOffsetDateTimeProv
import io.p8e.util.toProtoTimestampProv
import io.provenance.p8e.shared.domain.EnvelopeRecord
import java.time.OffsetDateTime

/**
 * Add expiration.
 */
fun EnvelopeRecord.addExpiration(): EnvelopeError =
    data.input.error("Envelope expired:${data.input.expirationTime.toInstantProv()}", EnvelopeError.Type.TTL_TIMEOUT)
        .also { data = newError(it) }

/**
 * Add chaincode error.
 *
 * @param [message] of the error
 */
fun EnvelopeRecord.addChaincodeError(message: String): EnvelopeError {
    return data.input.error(
        "Envelope chaincode error:$message",
        EnvelopeError.Type.CC_INVOCATION
    ).also { data = newError(it) }
}

/**
 * Add an envelope error to an envelope.
 *
 * @param [error] to add
 */
fun EnvelopeRecord.newError(error: EnvelopeError): EnvelopeState {
    val record = this
    return data.toBuilder()
        .also { builder ->
            if (!builder.hasErrorTime())
                error.auditFields.createdDate
                    .takeIf { !builder.hasErrorTime() }
                    ?.run {
                        record.errorTime = this.toOffsetDateTimeProv()
                        builder.errorTime = this
                    }
                    ?: run {
                        val now = OffsetDateTime.now()
                        record.errorTime = now
                        builder.errorTime = now.toProtoTimestampProv()
                    }
        }
        .also { builder ->
            // Only add if not already set previously
            if (builder.errorsList.none { it.uuid == error.uuid })
                builder.addErrors(error)
        }
        .auditedProv()
        .build()
}
