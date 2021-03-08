package io.provenance.os.baseclient.client

import com.google.protobuf.Message
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.domain.ObjectWithItem
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
        ownerPublicKey: PublicKey,
        signingKeyPair: KeyPair,
        additionalAudiences: Set<PublicKey> = setOf(),
        metadata: Map<String, String> = mapOf(),
        uuid: UUID = UUID.randomUUID()
    ): ObjectWithItem

    fun put(
        inputStream: InputStream,
        ownerPublicKey: PublicKey,
        signingKeyPair: KeyPair,
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
