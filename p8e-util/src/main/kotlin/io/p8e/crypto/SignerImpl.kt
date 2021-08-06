package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.proto.Common
import io.p8e.proto.PK
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.PublicKey

interface SignerImpl {

    companion object{
        // Algo must match Provenance-object-store
        val SIGN_ALGO_SHA_512_PREFIX = "SHA512"
        val SIGN_ALGO_SHA_256_PREFIX = "SHA256"
        val SIGN_ALGO_DETERMINISTIC_SUFFIX = "withECDDSA"
        val SIGN_ALGO_NON_DETERMINISTIC_SUFFIX = "withECDSA"
        val PROVIDER = BouncyCastleProvider.PROVIDER_NAME

        val DEFAULT_HASH = HashType.SHA512

        enum class HashType {
            SHA512,
            SHA256
        }
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

    fun initSign()

    fun signer(): PK.SigningAndEncryptionPublicKeys

    fun verify(publicKey: PublicKey, data: ByteArray, signature: Common.Signature): Boolean

    fun getPublicKey(): PublicKey

    var hashType: HashType

    var deterministic: Boolean

    val signAlgorithmPrefix
        get() = when (hashType) {
            HashType.SHA256 -> SIGN_ALGO_SHA_256_PREFIX
            HashType.SHA512 -> SIGN_ALGO_SHA_512_PREFIX
        }

    val signAlgorithmSuffix
        get() = if (deterministic) SIGN_ALGO_DETERMINISTIC_SUFFIX else SIGN_ALGO_NON_DETERMINISTIC_SUFFIX

    val signAlgorithm
        get() = signAlgorithmPrefix + signAlgorithmSuffix
}
