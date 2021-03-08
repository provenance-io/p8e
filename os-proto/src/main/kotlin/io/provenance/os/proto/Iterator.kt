package io.provenance.os.proto

import com.google.protobuf.ByteString
import io.provenance.os.proto.Objects.ChunkEnd
import java.io.InputStream

class InputStreamChunkedIterator(
    private val inputStream: InputStream,
    private val name: String,
    private val contentLength: Long,
    private val chunkSize: Int = 2 * 1_024 * 1_024 // ~2MB
) : Iterator<Objects.ChunkBidi> {
    private val header: Objects.StreamHeader = Objects.StreamHeader.newBuilder()
        .setName(this.name)
        .setContentLength(this.contentLength)
        .build()
    private var sentHeader = false
    private var sentEnd = false

    override fun hasNext(): Boolean = !sentEnd

    override fun next(): Objects.ChunkBidi {
        val bytes = ByteArray(chunkSize)
        val bytesRead = inputStream.read(bytes)

        return Objects.ChunkBidi.newBuilder()
            .setChunk(
                Objects.Chunk.newBuilder()
                    .also { builder ->
                        if (bytesRead < 0) {
                            builder.end = ChunkEnd.getDefaultInstance()
                            sentEnd = true
                        } else {
                            if (!sentHeader) {
                                builder.header = header
                                sentHeader = true
                            }

                            builder.data = ByteString.copyFrom(bytes, 0, bytesRead)
                        }
                    }
                    .build()
            )
            .build()
    }
}
