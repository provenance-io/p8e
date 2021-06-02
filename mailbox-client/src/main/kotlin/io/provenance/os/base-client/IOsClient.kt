package io.provenance.os.baseclient.client

import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.domain.ObjectWithItem
import io.provenance.p8e.encryption.model.KeyRef
import java.io.InputStream
import java.security.KeyPair
import java.security.PublicKey
import java.util.UUID

interface IOsClient {
    fun get(
        uri: String,
        publicKey: PublicKey
    ): DIMEInputStream

    fun get(
        sha512: ByteArray,
        publicKey: PublicKey
    ): DIMEInputStream

    fun put(
        message: Message,
        ownerEncryptionKeyRef: KeyRef,
        signer: SignerImpl,
        additionalAudiences: Set<PublicKey> = setOf(),
        metadata: Map<String, String> = mapOf(),
        uuid: UUID = UUID.randomUUID()
    ): ObjectWithItem

    fun put(
        inputStream: InputStream,
        ownerEncryptionKeyRef: KeyRef,
        signer: SignerImpl,
        contentLength: Long,
        additionalAudiences: Set<PublicKey> = setOf(),
        metadata: Map<String, String> = mapOf(),
        uuid: UUID = UUID.randomUUID()
    ): ObjectWithItem

    fun createPublicKey(
        publicKey: PublicKey
    ): io.provenance.os.domain.PublicKey

    fun deletePublicKey(
        publicKey: PublicKey
    ): io.provenance.os.domain.PublicKey?

    fun getallKeys(): List<io.provenance.os.domain.PublicKey>
}
