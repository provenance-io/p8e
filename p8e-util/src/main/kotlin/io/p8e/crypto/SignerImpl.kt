package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.proto.Common
import io.p8e.proto.PK
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.PublicKey

interface SignerImpl {

    companion object{
        // Algo must match Provenance-object-store
        val SIGN_ALGO = "SHA512withECDSA"
        val PROVIDER = BouncyCastleProvider.PROVIDER_NAME
    }

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

    fun verify(publicKey: PublicKey, data: ByteArray, signature: Common.Signature): Boolean

    fun initVerify(publicKey: PublicKey)

    fun initSign()

    fun signer(): PK.SigningAndEncryptionPublicKeys

    fun getPublicKey(): PublicKey
}
