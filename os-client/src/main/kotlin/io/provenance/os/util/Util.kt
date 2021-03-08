package io.provenance.os.util

import com.google.common.hash.Hashing
import java.io.InputStream
import java.util.Base64

const val CHUNK_SIZE = 2 * 1024 * 1024

fun <T : Any> T?.orThrowNotFound(message: String) = this?.let { it } ?: throw NotFoundException(
    message
)

class NotFoundException(message: String) : RuntimeException(message)

class FileExistsException: RuntimeException()

fun ByteArray.toHexString() = map { String.format("%02X", it) }.reduce { acc, s -> "$acc$s" }

fun <T: Any, X: Throwable> T?.orThrow(supplier: () -> X) = this?.let { it } ?: throw supplier()

fun <T: Any?> T?.orGet(supplier: () -> T) = this?.let { it } ?: supplier()

fun ByteArray.base64Encode() = Base64.getEncoder().encode(this)

fun String.base64Encode() = String(Base64.getEncoder().encode(toByteArray()))

fun String.base64Decode() = Base64.getDecoder().decode(this)

fun ByteArray.sha512() = Hashing.sha512().hashBytes(this).asBytes()!!

fun ByteArray.crc32c() = Hashing.crc32c().hashBytes(this).asBytes()!!

fun InputStream.readAllBytes(contentLength: Int) = use { inputStream ->
    ByteArray(contentLength).also { bytes ->
        var pos = 0
        var read: Int
        do {
            read = inputStream.read(bytes, pos, CHUNK_SIZE)
            pos += read
        } while (read > 0)
    }
}

object Util {
    fun getFullPath(bucket: String, name: String) = "$bucket/$name"
}
