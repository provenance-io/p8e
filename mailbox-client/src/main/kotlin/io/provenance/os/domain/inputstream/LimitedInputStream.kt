package io.provenance.os.domain.inputstream

import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

class LimitedInputStream(
    private val inputStream: InputStream,
    private val limit: Long,
    private val currentCount: AtomicLong
): InputStream() {

    override fun read(): Int {
        if (currentCount.get() == limit) {
            return -1
        }

        val result = inputStream.read()
        if (result == -1) {
            return result
        }
        currentCount.incrementAndGet()
        return result
    }

    override fun close() {
        //noop since we don't wanna preemptively close the input stream
    }
}