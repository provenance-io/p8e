package io.p8e.crypto

import java.io.InputStream
import java.security.PublicKey
import java.security.Signature

class SignatureInputStream(
    private val inputStream: InputStream,
    private val signer: SignerImpl? = null,
    private val verify: Signature? = null,
    private val signatureBytes: ByteArray? = null
): InputStream() {
    override fun read(): Int {
        val res = inputStream.read()
        if(res != -1) {
            if(signer != null) {
                signer.update(inputStream.readAllBytes())
            } else {
                verify?.update(inputStream.readAllBytes())
            }
        }
        return res
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int
    ): Int {
        val res = inputStream.read(b, off, len)
        if(res != -1) {
            if(signer != null) {
                signer.update(b, off, res)
            } else {
                verify?.update(b, off, res)
            }
        }
        return res
    }

    fun sign(): ByteArray {
        return signer!!.sign()
    }

    fun verify(): Boolean {
        if (signatureBytes == null) {
            throw IllegalStateException("Unable to verify signature input stream when in signing mode.")
        }
        return verify?.verify(signatureBytes)!!
    }
}

fun InputStream.sign(signer: SignerImpl): SignatureInputStream {
    return SignatureInputStream(
        inputStream = this,
        signer = signer.apply { initSign() }
    )
}

fun InputStream.verify(publicKey: PublicKey, signature: ByteArray): SignatureInputStream {
    return SignatureInputStream(
        inputStream = this,
        verify = Signature.getInstance(SignerImpl.SIGN_ALGO, SignerImpl.PROVIDER).apply { initVerify(publicKey) },
        signatureBytes = signature
    )
}
