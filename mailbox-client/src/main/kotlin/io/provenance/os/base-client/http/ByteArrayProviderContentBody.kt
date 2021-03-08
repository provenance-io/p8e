package io.provenance.os.baseclient.client.http

import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.content.ContentBody
import java.io.OutputStream

class ByteArrayProviderContentBody(
    val fieldName: String,
    val byteArrayProvider: () -> ByteArray
): ContentBody {
    override fun getFilename() = fieldName

    override fun getSubType() = ContentType.APPLICATION_OCTET_STREAM.mimeType

    override fun getTransferEncoding() = "7bit"

    override fun getCharset() = ContentType.APPLICATION_OCTET_STREAM.mimeType

    override fun writeTo(out: OutputStream?) {
        out?.write(byteArrayProvider())
    }

    override fun getMimeType() = ContentType.APPLICATION_OCTET_STREAM.mimeType

    override fun getContentLength() = (512 / 8).toLong()

    override fun getMediaType() = ContentType.APPLICATION_OCTET_STREAM.mimeType

}
