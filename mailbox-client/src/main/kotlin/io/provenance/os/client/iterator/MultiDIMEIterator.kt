package io.provenance.os.mailbox.client.iterator

import io.provenance.os.util.CertificateUtil
import io.provenance.os.domain.Signature
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.domain.inputstream.LimitedInputStream
import io.provenance.os.mailbox.client.MailboxClient
import org.apache.http.client.methods.CloseableHttpResponse
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MultiDIMEIterator(
    private val mailboxClient: MailboxClient,
    private val publicKey: ByteArray,
    private val inputStream: InputStream,
    private val sizes: List<Long>,
    private val objectPublicKeyUuids: List<UUID>,
    private val sha512s: List<ByteArray>,
    private val signatures: List<List<Signature>>,
    private val boundary: String
): Iterator<DIMEInputStreamResponse>, Closeable {

    private val boundarySize = boundary.toByteArray().size

    private var currentItem = -1
    private var currentCount = AtomicLong(0L)
    private var currentSize: Long? = null

    override fun hasNext(): Boolean {
        return currentItem < sizes.size - 1
    }

    override fun next(): DIMEInputStreamResponse {
        currentItem++
        if (currentItem > sizes.size - 1) {
            throw NoSuchElementException("No next item found.")
        }
        if (currentSize != null && currentCount.get() < currentSize!!) {
            // If we haven't fully read the current stream, seek to the end of it
            readUntilFull(ByteArray(currentSize!!.toInt()))
        }
        val b = ByteArray(boundarySize)
        // Read the boundary
        readUntilFull(b)
        if (b.toString(Charsets.UTF_8) != boundary) {
            throw IllegalStateException("Boundary read does not match boundary supplied. \nRead: [${b.toString(Charsets.UTF_8)}] \nSupplied: [$boundary]")
        }

        currentSize = sizes[currentItem]
        currentCount = AtomicLong(0L)

        return DIMEInputStreamResponse(
            DIMEInputStream.parse(
                LimitedInputStream(
                    inputStream,
                    currentSize!!,
                    currentCount
                ),
                signatures = signatures[currentItem]
            ),
            currentSize!!,
            sha512s[currentItem],
            signatures[currentItem],
            objectPublicKeyUuids[currentItem],
            publicKey
        ) { uuid, publicKey -> mailboxClient.ack(uuid, publicKey) }
    }

    override fun close() {
        inputStream.close()
    }

    private fun readUntilFull(bytes: ByteArray) {
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
