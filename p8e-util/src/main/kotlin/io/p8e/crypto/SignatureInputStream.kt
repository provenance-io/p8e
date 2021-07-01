package io.p8e.crypto

import java.io.InputStream
import java.security.PublicKey

class SignatureInputStream(
    private val inputStream: InputStream,
    private val signer: SignerImpl,
    private val signatureBytes: ByteArray? = null
): InputStream() {
    override fun read(): Int {
        val res = inputStream.read()
        if (res != -1) {
            signer.update(inputStream.readAllBytes())
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
            signer.update(b, off, res)
        }
        return res
    }

    fun sign(): ByteArray {
        return signer.sign()
    }

    fun verify(): Boolean {
        if (signatureBytes == null) {
            throw IllegalStateException("Unable to verify signature input stream when in signing mode.")
        }
        return signer.verify(signatureBytes)
    }
}

fun InputStream.sign(signer: SignerImpl): SignatureInputStream {
    return SignatureInputStream(
        this,
        signer = signer.apply {
            initSign()
        }
    )
}

fun InputStream.verify(signer: SignerImpl, publicKey: PublicKey, signature: ByteArray): SignatureInputStream {
    return SignatureInputStream(
        this,
        signer = signer.apply {
            initVerify(publicKey)
        },
        signature
    )
}
