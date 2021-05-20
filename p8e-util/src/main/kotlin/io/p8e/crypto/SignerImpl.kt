package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.proto.Common
import io.p8e.proto.PK
import java.security.PublicKey
import java.security.Signature

interface SignerImpl {

    /**
     * signer function implementation will be done by specific signers.
     */
    fun sign(data: String): Common.Signature

    fun sign(data: Message): Common.Signature

    fun sign(data: ByteArray): Common.Signature

    fun sign(): ByteArray

    fun update(data: Byte)

    fun update(data: ByteArray)

    fun update(data: ByteArray, off: Int, len: Int)

    fun verify(signatureBytes: ByteArray): Boolean

    fun verify(data: ByteArray, signature: Common.Signature): Boolean

    fun initVerify(publicKey: PublicKey)

    fun initSign()

    fun signer(): PK.SigningAndEncryptionPublicKeys

    fun getPublicKey(): PublicKey
}
