package io.p8e.crypto

import io.p8e.crypto.SignerType.SIGN
import io.p8e.crypto.SignerType.VERIFY
import java.io.InputStream
import java.security.PublicKey
import java.security.Signature

enum class SignerType { SIGN, VERIFY }
data class SignatureRef(val signer: SignerImpl? = null, val verify: Signature? = null, val signerType: SignerType)

class SignatureInputStream(
    private val inputStream: InputStream,
    private val signatureRef: SignatureRef,
    private val signatureBytes: ByteArray? = null
): InputStream() {
    override fun read(): Int {
        val res = inputStream.read()
        if(res != -1) {
           if(signatureRef.signerType == SIGN) {
               signatureRef.signer?.update(inputStream.readAllBytes())
           } else {
               signatureRef.verify?.update(inputStream.readAllBytes())
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
            if(signatureRef.signerType == SIGN) {
                signatureRef.signer?.update(b, off, res)
            } else {
                signatureRef.verify?.update(b, off, res)
            }
        }
        return res
    }

    fun sign(): ByteArray {
        return signatureRef.signer!!.sign()
    }

    fun verify(): Boolean {
        if (signatureBytes == null) {
            throw IllegalStateException("Unable to verify signature input stream when in signing mode.")
        }
        return signatureRef.verify!!.verify(signatureBytes)
    }
}

fun InputStream.sign(signer: SignerImpl): SignatureInputStream {
    return SignatureInputStream(
        inputStream = this,
        signatureRef = SignatureRef(signer.apply{ initSign() }, null, SIGN)
    )
}

fun InputStream.verify(publicKey: PublicKey, signature: ByteArray): SignatureInputStream {
    val verifySig = Signature.getInstance(SignerImpl.SIGN_ALGO, SignerImpl.PROVIDER).apply { initVerify(publicKey) }
    return SignatureInputStream(
        inputStream = this,
        signatureRef = SignatureRef(null, verifySig, VERIFY),
        signatureBytes = signature
    )
}
