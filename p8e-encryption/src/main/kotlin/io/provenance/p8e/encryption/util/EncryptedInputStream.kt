package io.provenance.p8e.encryption.util

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.CipherInputStream

class EncryptedInputStream(val header: ByteArray, val inputStream: HashingCipherInputStream, val hashProvider: () -> ByteArray): InputStream() {

    private var pos = 0

    val closed = AtomicBoolean(false)

    val hashed = AtomicBoolean(false)

    override fun read(): Int {
        if (closed.get()) {
            throw IllegalStateException("InputStream has been closed.")
        }
        return if (pos >= header.size) {
            inputStream.read()
        } else {
            // Bitwise and removed the one's complement (which makes 0xFF appear to be -1 when converted to int)
            header[pos++].toInt() and 0xFF
        }
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        if (pos >= header.size) {
            val res = inputStream.read(b, off, len)
            pos += res
            return res
        } else {
            val sizeLeftInHeader = header.size - pos
            val bytesToRead = if (sizeLeftInHeader > len) {
                len
            } else {
                sizeLeftInHeader
            }
            System.arraycopy(header, pos, b, off, bytesToRead)
            pos += bytesToRead
            return bytesToRead
        }
    }

    override fun close() {
        closed.set(true)
        inputStream.close()
    }

    fun hash() = hashed.takeIf { !it.get() }
        .orThrow { IllegalStateException("Behavior undefined for multiple calls to hash.")}
        .let {
            it.set(true)
            hashProvider()
        }
}

fun <T: Any, X: Throwable> T?.orThrow(supplier: () -> X) = this?.let { it } ?: throw supplier()
