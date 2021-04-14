package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.proto.Common
import io.p8e.proto.PK
import io.p8e.proto.ProtoUtil
import io.p8e.util.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.security.Signature

class Pen: SignerImpl() {

    companion object {
        // Algo must match Provenance-object-store
        val SIGN_ALGO = "SHA512withECDSA"
        val KEY_TYPE = "ECDSA"
        val PROVIDER = "BC"
    }

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    var keys: KeyPair? = null
    var lens: Lens? = null

    override fun setKeyId(keyPair: KeyPair) {
        keys = keyPair
        lens = Lens(keys!!.public)
    }

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
        val s = Signature.getInstance(
            SIGN_ALGO,
            PROVIDER
        )
        s.initSign(keys?.private)
        s.update(data)

        return ProtoUtil
            .signatureBuilderOf(String(s.sign().base64Encode()))
            .setSigner(lens?.signer())
            .build()
            .takeIf { lens!!.verify(data, it) }
            .orThrow { IllegalStateException("can't verify signature - public cert may not match private key.") }
    }
}

/**
 * Allows inspection of data that is signed by a Provenance Member Certificate
 *
 * @param pubKey The public key to be used for examination.
 */
class Lens(val publicKey: PublicKey) {
    // TODO - Verify data hash... ???
    fun verify(data: String, signature: Common.Signature) = verify(data.base64Decode(), signature)

    fun verify(data: ByteArray, signature: Common.Signature): Boolean {
        val s = Signature.getInstance(signature.algo, signature.provider)
        s.initVerify(publicKey)
        s.update(data)
        return s.verify(signature.signature.base64Decode())
    }

    fun signer(): PK.SigningAndEncryptionPublicKeys =
        PK.SigningAndEncryptionPublicKeys.newBuilder()
            .setSigningPublicKey(
                publicKey.toPublicKeyProto()
            ).build()

}
