package io.provenance.p8e.encryption.util

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException

class HashingCipherInputStream(
    inputStream: InputStream,
    private val cipher: Cipher,
    private val digest: MessageDigest,
    private val header: ByteArray = ByteArray(0)
): FilterInputStream(inputStream) {
    private var done: Boolean = false
    // Raw buffer to read bytes from stream
    private val rawBuffer = ByteArray(32768)

    // Position marker for when to read out of header
    private var pos = 0

    // Buffer to hold ciphered bytes, should be larger than rawBuffer for block size reasons
    private var cipheredBuffer = ByteArray(rawBuffer.size + 32)
    private var cipheredStart = 0
    private var cipheredEnd = 0

    override fun close() {
        `in`.close()
    }

    override fun markSupported(): Boolean {
        return false
    }

    override fun read(): Int {
        if (pos >= header.size) {
            if (cipheredStart >= cipheredEnd) {
                // we loop for new data as the spec says we are blocking
                var i = 0
                while (i == 0) i = getMoreData()
                if (i == -1) return -1
            }
            return cipheredBuffer[cipheredStart++].toInt() and 0xff
        } else {
            // Bitwise and removed the one's complement (which makes 0xFF appear to be -1 when converted to int)
            return header[pos++].toInt() and 0xFF
        }
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        if (pos >= header.size) {
            if (cipheredStart >= cipheredEnd) {
                // we loop for new data as the spec says we are blocking
                var i = 0
                while (i == 0) i = getMoreData()
                if (i == -1) return -1
            }
            if (len <= 0) {
                return 0
            }
            var available = cipheredEnd - cipheredStart
            if (len < available) available = len
            if (b != null) {
                System.arraycopy(cipheredBuffer, cipheredStart, b, off, available)
            }
            cipheredStart += available
            pos += available
            return available
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

    fun hash(): ByteArray {
        return digest.digest()
    }

    private fun getMoreData(): Int {
        if (done) return -1
        val read = `in`.read(rawBuffer, 0, rawBuffer.size)
        if (read == -1) {
            done = true
            try {
                cipheredBuffer = cipher.doFinal()
                cipheredEnd = cipheredBuffer.size
            } catch (e: IllegalBlockSizeException) {
                cipheredBuffer.fill(0)
                throw IOException(e)
            } catch (e: BadPaddingException) {
                cipheredBuffer.fill(0)
                throw IOException(e)
            }

            cipheredStart = 0
            return cipheredEnd
        }
        try {
            cipheredEnd = cipher.update(rawBuffer, 0, read, cipheredBuffer)
            digest.update(rawBuffer, 0, read)
        } catch (e: IllegalStateException) {
            cipheredBuffer.fill(0)
            throw e
        }
        cipheredStart = 0
        return cipheredEnd
    }
}
