package io.provenance.pbc.clients.p8e

import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.provenance.pbc.clients.Contracts
import io.provenance.pbc.clients.MemorializeContractRequest
import io.provenance.pbc.clients.OwnershipChangeRequest
import io.provenance.pbc.clients.tx.TxPreparer
import io.provenance.pbc.p8e.ext.toPbc
import org.slf4j.LoggerFactory
import java.util.UUID
import io.p8e.proto.ContractScope.Envelope as P8EEnvelope
import io.p8e.proto.Util.UUID as ProtoUUID

private val log = LoggerFactory.getLogger(Contracts::class.java)

internal fun Message.toJson() = JsonFormat.printer().print(this)

private fun ProtoUUID.toUuidOrNull(): UUID? =
        try {
            toUuid()
        } catch (e: IllegalArgumentException) {
            null
        }

private fun ProtoUUID.toUuid(): UUID = UUID.fromString(value)

// TODO can this class be removed?
/**
 * Submit a p8e contract to blockchain.
 * @param contract The p8e contract that has been processed and needs memorialized.
 */
fun Contracts.memorializeP8EContract(envelope: P8EEnvelope): TxPreparer = { base ->
    log.trace("submitContract(exec:${envelope.contract.definition.name})")
    log.trace("contract:${envelope.toJson()}")
    log.trace("convertedContract:${envelope.contract.toPbc().toJson()}")
    log.trace("convertedSignatures:${envelope.signaturesList.toPbc()}")

    prepareMemorializeContract(MemorializeContractRequest(
            base,
            envelope.ref.scopeUuid.toUuid(),
            envelope.ref.groupUuid.toUuid(),
            envelope.executionUuid.toUuid(),
            envelope.contract.toPbc(),
            envelope.signaturesList.toPbc(),
            envelope.scope.uuid.toUuidOrNull() ?: UUID.randomUUID()
    ))
}

fun Contracts.changeScopeOwnership(envelope: P8EEnvelope): TxPreparer = { base ->
    log.trace("submitContract(exec:${envelope.contract.definition.name})")
    log.trace("contract:${envelope.toJson()}")
    log.trace("convertedContract:${envelope.contract.toPbc().toJson()}")
    log.trace("convertedSignatures:${envelope.signaturesList.toPbc()}")

    prepareChangeOwnership(
        OwnershipChangeRequest(
            base,
            scope_id = envelope.ref.scopeUuid.toUuid(),
            group_id = envelope.ref.groupUuid.toUuid(),
            execution_id = envelope.executionUuid.toUuid(),
            contract = envelope.contract.toPbc(),
            signatures = envelope.signaturesList.toPbc(),
            recitals = null,
            invoker = envelope.contract.invoker.signingPublicKey.toString()
        )
    )
}
