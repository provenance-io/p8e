package io.provenance.os.domain.inputstream

import io.provenance.os.domain.inputstream.SignatureInputStream.Companion.PROVIDER
import io.provenance.os.domain.inputstream.SignatureInputStream.Companion.SIGN_ALGO
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

class SignatureInputStream(
    private val inputStream: InputStream,
    private val signature: Signature,
    private val signatureBytes: ByteArray? = null
): InputStream() {
    override fun read(): Int {
        val res = inputStream.read()
        if (res != -1) {
            signature.update(res.toByte())
        }
        return res
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int
    ): Int {
        val res = inputStream.read(b, off, len)
        if (res != -1) {
            signature.update(b, off, res)
        }
        return res
    }

    fun sign(): ByteArray {
        return signature.sign()
    }

    fun verify(): Boolean {
        if (signatureBytes == null) {
            throw IllegalStateException("Unable to verify signature input stream when in signing mode.")
        }
        return signature.verify(signatureBytes)
    }

    companion object {
        const val SIGN_ALGO = "SHA512withECDSA"
        const val KEY_TYPE = "ECDSA"
        const val PROVIDER = BouncyCastleProvider.PROVIDER_NAME
    }
}

fun InputStream.sign(privateKey: PrivateKey): SignatureInputStream {
    return SignatureInputStream(
        this,
        Signature.getInstance(
            SIGN_ALGO,
            PROVIDER
        ).apply {
            initSign(privateKey)
        })
}

fun InputStream.verify(
    publicKey: PublicKey,
    signature: ByteArray
): SignatureInputStream {
    return SignatureInputStream(
        this,
        Signature.getInstance(
            SIGN_ALGO,
            PROVIDER
        ).apply {
            initVerify(publicKey)
        },
        signature
    )
}