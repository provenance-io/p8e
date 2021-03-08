package io.provenance.p8e.encryption.util

import java.math.BigInteger
import kotlin.experimental.and

object ByteUtil {
    fun getUInt32(bytes: ByteArray): Long {
        check(bytes.size == 4) { "4 bytes are required to get UInt32" }
        return bytes[0].toLong() and 0xFF shl 24 or (bytes[1].toLong() and 0xFF shl 16) or (bytes[2].toLong() and 0xFF shl 8) or (bytes[3]
            .toLong() and 0xFF)
    }

    fun getUInt16(bytes: ByteArray): Int {
        check(bytes.size == 2) { "2 bytes are required to get UInt16" }
        return bytes[0].toInt() and 0xFF shl 8 or (bytes[1].toInt() and 0xFF)
    }

    fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
        require(bytes.size >= offset + 4) { "Provided offset + 4 bytes would overflow buffer." }
        bytes[offset] = (value and -0x1000000 shr 24).toByte()
        bytes[offset + 1] = (value and 0x00FF0000 shr 16).toByte()
        bytes[offset + 2] = (value and 0x0000FF00 shr 8).toByte()
        bytes[offset + 3] = (value and 0x000000FF).toByte()
    }

    fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        require(bytes.size >= offset + 2) { "Provided offset + 2 bytes would overflow buffer." }
        bytes[offset] = (value and 0xFF00 shr 8).toByte()
        bytes[offset + 1] = (value and 0x00FF).toByte()
    }

    fun unsignedBytesToBigInt(bytes: ByteArray): BigInteger {
        var result: BigInteger = BigInteger.ZERO
        for (i in 1..bytes.size) {
            val value: Long = (bytes[bytes.size - i] and 0xFF.toByte()).toUByte().toLong()
            result = result.add(BigInteger.valueOf(value).shiftLeft((i - 1) * 8))
        }
        return result
    }
}
