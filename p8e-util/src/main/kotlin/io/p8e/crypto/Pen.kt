package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl.Companion.PROVIDER
import io.p8e.proto.Common
import io.p8e.proto.PK
import io.p8e.proto.ProtoUtil
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.Signature

class Pen(
    private val keyPair: KeyPair
): SignerImpl {
    init {
        Security.addProvider(BouncyCastleProvider())
    }

    val privateKey: PrivateKey = keyPair.private

    override var hashType = SignerImpl.DEFAULT_HASH
        set(value) {
            field = value
            resetSignature()
        }

    override var deterministic: Boolean = false
        set(value) {
            field = value
            resetSignature()
        }

    private var signature: Signature = getNewSignatureInstance()

    private fun resetSignature() {
        logger().info("setting signature to algorithm $signAlgorithm")
        signature = getNewSignatureInstance()
    }

    private fun getNewSignatureInstance() = Signature.getInstance(
        signAlgorithm,
        PROVIDER
    )

    /**
     * Return the signing public key.
     */
    override fun getPublicKey(): PublicKey = keyPair.public

    /**
     * Sign protobuf data.
     */
    override fun sign(data: Message) = sign(data.toByteArray())

    /**
     * Sign string data.
     */
    override fun sign(data: String) = sign(data.toByteArray())

    /**
     * Sign byte array.
     */
    override fun sign(data: ByteArray): Common.Signature {
        signature.initSign(privateKey)
        signature.update(data)

        return ProtoUtil
            .signatureBuilderOf(String(signature.sign().base64Encode()), signature.algorithm)
            .setSigner(signer())
            .build()
            .takeIf { verify(keyPair.public, data, it) }
            .orThrow { IllegalStateException("can't verify signature - public cert may not match private key.") }
    }

    override fun sign(): ByteArray = signature.sign()

    override fun update(data: ByteArray) = signature.update(data)

    override fun update(data: ByteArray, off: Int, res: Int) {
        signature.update(data, off, res)
    }

    override fun update(data: Byte) { signature.update(data) }

    override fun verify(signatureBytes: ByteArray): Boolean = signature.verify(signatureBytes)

    override fun initVerify(publicKey: PublicKey) {
        signature.initVerify(publicKey)
    }

    override fun initSign() {
        signature.initSign(keyPair.private)
    }

    override fun verify(publicKey: PublicKey, data: ByteArray, signature: Common.Signature): Boolean =
        Signature.getInstance(signature.algo, signature.provider)
            .apply {
                initVerify(publicKey)
                update(data)
            }.verify(signature.signature.base64Decode())

    override fun signer(): PK.SigningAndEncryptionPublicKeys =
        PK.SigningAndEncryptionPublicKeys.newBuilder()
            .setSigningPublicKey(keyPair.public.toPublicKeyProto())
            .build()
}
