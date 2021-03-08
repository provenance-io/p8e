package io.provenance.engine.extension

import io.p8e.proto.Common.Location
import io.p8e.proto.Common.ProvenanceReference
import io.p8e.proto.Common.Signature
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.EnvelopeError
import io.p8e.proto.ContractScope.EnvelopeState
import io.p8e.proto.Contracts.ProposedFact
import io.p8e.util.base64Encode
import io.p8e.util.toHex
import io.p8e.util.auditedProv
import io.p8e.util.randomProtoUuidProv
import io.p8e.util.toProtoTimestampProv
import java.time.OffsetDateTime

/**
 * Merge signature of envelope with result envelope.
 *
 * @param [env] The other party executed envelope with signature
 */
fun Envelope.Builder.addSignature(env: Envelope): Envelope.Builder {
    require(env.signaturesCount == 1) { "Executed contract must have a signature" }

    return addSignatures(env.signaturesList.first())
}

fun Envelope.hasSignature(signature: Signature): Boolean = signaturesList.any { it.signer.signingPublicKey.toHex() ==
        signature.signer.signingPublicKey.toHex() }

/**
 * Determine if an envelope is fully signed by comparing its signatures with recitals.
 */
fun Envelope.isSigned(): Boolean {
    return contract.recitalsList
        .filter { it.hasSigner() }
        .map { it.signer.signingPublicKey.toHex() } // needs to be public key, proto issue
        .toSet()
        .plus(
            scope.partiesList
                .filter { it.hasSigner() }
                .map { it.signer.signingPublicKey.toHex() }
                .toSet()
        )
        .all { publicKey -> signaturesList.any { publicKey == it.signer.signingPublicKey.toHex() } }
}


/**
 * Wrap a new envelope in an EnvelopeState to track inputs, results and timestamps.
 *
 * @param [result] The result of the input envelope
 */
fun Envelope.wrap(result: Envelope): EnvelopeState = EnvelopeState.newBuilder()
    .also { builder ->
        builder.input = this
        builder.result = result
        builder.isInvoker = true
        builder.contractClassname = contract.definition.resourceLocation.classname
    }
    .auditedProv()
    .build()

/**
 * Get a collection of all the envelope/contract proposed facts.
 */
fun Envelope.proposedFacts(): Set<ProposedFact> = contract.considerationsList.flatMap { it.inputsList }.toSet()

/**
 * Create an error
 */
fun Envelope.error(message: String, type: EnvelopeError.Type): EnvelopeError =
    EnvelopeError.newBuilder()
        .setUuid(randomProtoUuidProv())
        .setEnvelope(this)
        .setExecutionUuid(executionUuid)
        .setGroupUuid(ref.groupUuid)
        .setScopeUuid(ref.scopeUuid)
        .setType(type)
        .setMessage(message)
        .auditedProv()
        .build()

/**
 * Wrap a fragmented envelope in an EnvelopeState to track inputs, results and timestamps.
 */
fun Envelope.stage(): EnvelopeState = EnvelopeState.newBuilder()
    .also { builder ->
        builder.input = this
        builder.isInvoker = false
        builder.inboxTime = OffsetDateTime.now().toProtoTimestampProv()
        builder.contractClassname = contract.definition.resourceLocation.classname
    }
    .auditedProv()
    .build()

/**
 * Convert an ObjectWithItem to a Location.
 */
fun ByteArray.toProto(): Location = Location.newBuilder()
    .setRef(ProvenanceReference.newBuilder().setHash(String(base64Encode())))
    .build()
