package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl.Companion.OBJECT_SIZE_BYTES
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

    private var verifying: Boolean = false
    private var objSizeIndexer: Int = OBJECT_SIZE_BYTES
    private var aggregatedData: ByteArray? = null

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
            .takeIf { verify(data, it) }
            .orThrow { IllegalStateException("can't verify signature - public cert may not match private key.") }
    }

    override fun sign(): ByteArray {
        // Only update if the aggregated data is not null
        if(aggregatedData != null) {
            signature.update(aggregatedData)
        }

        // null out the aggregatedData value to reset for next verify/sign
        return signature.sign().also { aggregatedData = null }
    }

    override fun update(data: ByteArray) = signature.update(data)

    override fun update(data: ByteArray, off: Int, res: Int) {
        // If off is less then res, these are the data that we care about.
        if(off < res) {
            if(!verifying) {
                val dataSample = data.copyOfRange(off, off+res)
                aggregatedData = dataSample
            } else {
                /**
                 * The downstream (data verification) chunks the data into data size of 8192.
                 * The data needs to be aggregated to its signing size of 32768 before the
                 * data can be validated.
                 */
                if(objSizeIndexer == OBJECT_SIZE_BYTES) {
                    objSizeIndexer = (off + res)
                    aggregatedData = if (aggregatedData == null) {
                        data.copyOfRange(off, off + res)
                    } else {
                        aggregatedData?.plus(data.copyOfRange(off, off + res))
                    }
                } else {
                    objSizeIndexer += (off + res)
                    aggregatedData = aggregatedData?.plus(data.copyOfRange(off, off + res))
                }
            }
        }
    }

    override fun update(data: Byte) { signature.update(data) }

    override fun verify(signatureBytes: ByteArray): Boolean {
        signature.update(aggregatedData)

        // Reset the object size indexer and null out the aggregatedData value.
        objSizeIndexer = OBJECT_SIZE_BYTES.also { aggregatedData = null }

        return signature.verify(signatureBytes)
    }

    override fun initVerify(publicKey: PublicKey) {
        signature.initVerify(publicKey)
        verifying = true
    }

    override fun initSign() {
        signature.initSign(keyPair.private)
        verifying = false
    }

    override fun verify(data: ByteArray, signature: Common.Signature): Boolean =
        Signature.getInstance(signature.algo, signature.provider)
            .apply {
                initVerify(keyPair.public)
                update(data)
            }.verify(signature.signature.base64Decode())

    override fun signer(): PK.SigningAndEncryptionPublicKeys =
        PK.SigningAndEncryptionPublicKeys.newBuilder()
            .setSigningPublicKey(keyPair.public.toPublicKeyProto())
            .build()
}
