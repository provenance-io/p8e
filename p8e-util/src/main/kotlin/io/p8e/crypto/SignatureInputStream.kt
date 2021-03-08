package io.p8e.crypto

import java.io.InputStream
import java.security.Signature

class SignatureInputStream(
    private val inputStream: InputStream,
    private val signature: Signature
): InputStream() {
    override fun read(): Int {
        val res = inputStream.read()
        if (res != -1) {
            signature.update(res.toByte())
        }
        return res
    }

    fun sign(): ByteArray {
        return signature.sign()
    }
}