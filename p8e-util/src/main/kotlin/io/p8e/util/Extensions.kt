package io.p8e.util

import com.google.common.hash.Hashing
import com.google.common.io.BaseEncoding
import io.p8e.proto.Common.ProvenanceReference
import io.provenance.p8e.shared.extension.logger
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64

const val CHUNK_SIZE = 2 * 1024 * 1024

fun ByteArray.toHexString() = BaseEncoding.base16().encode(this)

fun String.hexStringToByteArray() = BaseEncoding.base16().decode(this)

fun <T : Any, X : Throwable> T?.orThrow(supplier: () -> X) = this?.let { it } ?: throw supplier()

fun <T: Any> T?.or(supplier: () -> T) = this?.let { it } ?: supplier()

fun <T : Any?> T?.orGet(supplier: () -> T) = this?.let { it } ?: supplier()

fun ByteArray.base64Encode() = Base64.getEncoder().encode(this)

fun ByteArray.base64String() = String(Base64.getEncoder().encode(this))

fun ByteArray.base64Decode() = Base64.getDecoder().decode(this)

fun String.base64Encode() = String(Base64.getEncoder().encode(toByteArray()))

fun String.base64Decode() = Base64.getDecoder().decode(this)


fun ByteArray.sha512(): ByteArray = Hashing.sha512().hashBytes(this).asBytes()

fun ByteArray.base64Sha512() = this.sha512().base64String()


fun ByteArray.crc32c(): ByteArray = Hashing.crc32c().hashBytes(this).asBytes()

fun InputStream.readAllBytes(contentLength: Int) = use { inputStream ->
    ByteArray(contentLength).also { bytes ->
        var pos = 0
        var read: Int
        do {
            read = inputStream.read(bytes, pos,
                                    CHUNK_SIZE
            )
            pos += read
        } while (read > 0)
    }
}

fun String.getenvOrThrowNotFound() = System.getenv(this)
    .orThrowNotFound("Unable to find environment variable $this.")

fun String.readToString() = FileInputStream(File(this))
    .readAllBytes()!!
    .toString(Charsets.UTF_8)

object Util {
    fun getFullPath(bucket: String, name: String) = "$bucket/$name"
}

private val timerLogger = logger()
typealias TimeInterceptor = (aspect: String, start: OffsetDateTime, end: OffsetDateTime) -> Unit
private val timeInterceptors: MutableList<TimeInterceptor> = mutableListOf()
fun registerTimeInterceptor(interceptor: TimeInterceptor) = timeInterceptors.add(interceptor)

fun <R> timed(item: String, fn: () -> R): R {
    val start = OffsetDateTime.now()
    try {
        return fn()
    } finally {
        val end = OffsetDateTime.now()
        val elapsed = Duration.between(start, end)
        timerLogger.info("timed($item) => elapsed:${elapsed.toMillis()}ms  (start:$start end:$end)")
        try {
            timeInterceptors.forEach {
                it(item, start, end)
            }
        } catch (t: Throwable) {
            timerLogger.error("Error when executing time interceptors", t)
        }
    }
}

/**
 * Convert/wrap a string (hash) to a ProvenanceReference proto.
 */
fun String.toProvRef(): ProvenanceReference = ProvenanceReference.newBuilder().setHash(this).build()

/**
 * Stack trace from exception for logging purposes
 */
fun <T: Throwable> T.toMessageWithStackTrace(): String {
    val writer = StringWriter()
    val printWriter = PrintWriter(writer)
    printWriter.write("$message\n\n")
    printStackTrace(printWriter)
    return writer.toString()
}

