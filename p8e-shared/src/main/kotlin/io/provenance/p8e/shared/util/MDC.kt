package io.provenance.p8e.shared.util

import io.p8e.proto.ContractScope
import io.p8e.proto.Envelope
import io.p8e.util.toHex
import io.provenance.p8e.shared.domain.EnvelopeRecord
import org.slf4j.MDC
import java.security.PublicKey

object Label {
    const val ENVELOPE = "envelope"
    const val EXECUTION = "execution"
    const val GROUP = "group"
    const val SCOPE = "scope"
    const val PUBLIC_KEY = "public_key"
    const val TRANSACTION_HASHES = "transaction_hashes"
    const val BLOCK_HEIGHT = "block_height"
}

object P8eMDC {
    fun set(publicKey: PublicKey, clear: Boolean = false) = apply {
        if (clear) { MDC.clear() }

        MDC.put(Label.PUBLIC_KEY, publicKey.toHex())
    }

    fun set(envelopeEvent: Envelope.EnvelopeEvent, clear: Boolean = false) = apply {
        if (clear) { MDC.clear() }

        MDC.put(Label.EXECUTION, envelopeEvent.envelope.executionUuid.value)
        MDC.put(Label.GROUP, envelopeEvent.envelope.ref.groupUuid.value)
        MDC.put(Label.SCOPE, envelopeEvent.envelope.ref.scopeUuid.value)
    }

    fun set(envelopeRecord: EnvelopeRecord, clear: Boolean = false) = apply {
        if (clear) { MDC.clear() }

        MDC.put(Label.ENVELOPE, envelopeRecord.uuid.value.toString())
        MDC.put(Label.EXECUTION, envelopeRecord.executionUuid.toString())
        MDC.put(Label.GROUP, envelopeRecord.groupUuid.toString())
        MDC.put(Label.SCOPE, envelopeRecord.data.input.scope.uuid.value)
    }

    fun set(scope: ContractScope.Scope, clear: Boolean = false) = apply {
        if (clear) { MDC.clear() }

        MDC.put(Label.EXECUTION, scope.lastEvent.executionUuid.value)
        MDC.put(Label.GROUP, scope.lastEvent.groupUuid.value)
        MDC.put(Label.SCOPE, scope.uuid.value)
    }

    fun set(txHashes: TransactionHashes, clear: Boolean = false) = apply {
        if (clear) { MDC.clear() }

        MDC.put(Label.TRANSACTION_HASHES, txHashes.hashes.joinToString())
    }

    fun set(blockHeight: BlockHeight, clear: Boolean = false) = apply {
        if (clear) { MDC.clear() }

        MDC.put(Label.BLOCK_HEIGHT, blockHeight.height.toString())
    }
}

data class TransactionHashes(val hashes: List<String>)
fun String.toTransactionHashes() = TransactionHashes(listOf(this))
fun List<String>.toTransactionHashes() = TransactionHashes(this)

data class BlockHeight(val height: Long)
fun Long.toBlockHeight() = BlockHeight(this)
