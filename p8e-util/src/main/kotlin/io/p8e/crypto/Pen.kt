package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl.Companion.PROVIDER
import io.p8e.crypto.SignerImpl.Companion.SIGN_ALGO
import io.p8e.proto.Common
import io.p8e.proto.PK
import io.p8e.proto.ProtoUtil
import io.p8e.util.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.Signature

class Pen(
    private val keyPair: KeyPair
): SignerImpl {

    val privateKey: PrivateKey = keyPair.private

    val signature: Signature = Signature.getInstance(
        SIGN_ALGO,
        PROVIDER
    )

    init {
        Security.addProvider(BouncyCastleProvider())
    }

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
            .signatureBuilderOf(String(signature.sign().base64Encode()))
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
