package io.provenance.os.mailbox.client

import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl
import io.provenance.os.mailbox.client.iterator.MultiDIMEIterator
import io.provenance.os.domain.ObjectWithItem
import io.provenance.os.domain.Scope
import io.provenance.os.domain.Scope.GENERAL
import io.provenance.os.domain.inputstream.DIMEInputStream
import java.io.InputStream
import java.security.KeyPair
import java.security.PublicKey
import java.util.UUID

interface IMailboxClient {
    fun poll(
        publicKey: PublicKey,
        scope: Scope = GENERAL,
        limit: Int = 10
    ): MultiDIMEIterator

    fun put(
        message: Message,
        ownerPublicKey: PublicKey,
        signer: SignerImpl,
        additionalAudiences: Set<PublicKey> = setOf(),
        metadata: Map<String, String> = mapOf(),
        uuid: UUID = UUID.randomUUID()
    ): ObjectWithItem

    fun put(
        inputStream: InputStream,
        ownerPublicKey: PublicKey,
        signer: SignerImpl,
        contentLength: Long,
        additionalAudiences: Set<PublicKey> = setOf(),
        metadata: Map<String, String> = mapOf(),
        uuid: UUID = UUID.randomUUID()
    ): ObjectWithItem

    fun put(
        dimeInputStream: DIMEInputStream,
        contentLength: Long,
        signingPublicKey: PublicKey,
        signatureProvider: () -> ByteArray,
        hashProvider: () -> ByteArray,
        scope: Scope = GENERAL
    ): ObjectWithItem

    fun ack(
        objectPublicKeyUuid: UUID,
        publicKey: ByteArray
    ): Boolean

    fun isAcked(
        objectUuid: UUID,
        publicKey: ByteArray
    ): Boolean
}
