package io.provenance.os.mailbox.client.iterator

import io.provenance.os.domain.Signature
import io.provenance.os.domain.inputstream.DIMEInputStream
import java.security.PublicKey
import java.util.UUID

class DIMEInputStreamResponse(
    val dimeInputStream: DIMEInputStream,
    val contentLength: Long,
    val sha512: ByteArray,
    val signatures: List<Signature>,
    private val objectPublicKeyUUID: UUID,
    private val publicKey: ByteArray,
    private val ackFunction: (uuid: UUID, publicKey: ByteArray) -> Boolean
) {
    fun ack(): Boolean {
        return ackFunction(objectPublicKeyUUID, publicKey)
    }
}