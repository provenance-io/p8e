package io.provenance.os.mailbox.client.inputstream

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.provenance.p8e.encryption.util.ByteUtil
import io.p8e.util.configureProvenance
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

class HeaderInputStream<T>(
    val inputStream: InputStream,
    val header: T
): InputStream() {
    private val headerBuffer: ByteArray
    private var pos: Int = 0
    private val tBytes = objectMapper.writeValueAsBytes(header)

    init {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES + tBytes.size)
        buffer.putInt(tBytes.size)
        buffer.put(tBytes)
        headerBuffer = buffer.array()
    }

    override fun read(): Int {
        if (pos < headerBuffer.size) {
            return headerBuffer[pos++].toInt() and 0xFF
        } else {
            return inputStream.read()
        }
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= headerBuffer.size) {
            val res = inputStream.read(b, off, len)
            if (res != -1) {
                pos += res
            }
            return res
        } else {
            if (pos + len > headerBuffer.size) {
                val sizeLeftInHeader = headerBuffer.size - pos
                System.arraycopy(headerBuffer, pos, b, off, sizeLeftInHeader)
                val streamRead = inputStream.read(b, off + sizeLeftInHeader, len - sizeLeftInHeader)
                val totalRead = if (streamRead < 0) {
                    sizeLeftInHeader
                } else {
                    sizeLeftInHeader + streamRead
                }

                pos += totalRead
                return totalRead
            } else {
                System.arraycopy(headerBuffer, pos, b, off, len)

                pos += len
                return len
            }
        }
    }

    companion object {
        val objectMapper = ObjectMapper().configureProvenance()

        inline fun <reified T> parse(inputStream: InputStream): HeaderInputStream<T> {
            val tLength = ByteArray(Int.SIZE_BYTES).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(ByteUtil::getUInt32)

            val t = ByteArray(tLength.toInt()).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let { objectMapper.readValue<T>(it) }
            return HeaderInputStream(inputStream, t)
        }

        fun readUntilFull(inputStream: InputStream, bytes: ByteArray) {
            if (bytes.isEmpty()) {
                return
            }
            var read = 0
            do {
                val res = inputStream.read(bytes, read, bytes.size - read)
                if (res < 0) {
                    throw EOFException("End of input stream reached.")
                }
                read += res
            } while (read < bytes.size)
        }
    }
}
