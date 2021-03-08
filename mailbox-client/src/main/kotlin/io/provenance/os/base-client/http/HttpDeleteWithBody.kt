package io.provenance.os.baseclient.client.http

import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import java.net.URI

/**
 * Allows sending a DELETE request with a request body.
 */
class HttpDeleteWithBody(uri: String) : HttpEntityEnclosingRequestBase() {

    init {
        setURI(URI.create(uri))
    }

    override fun getMethod(): String {
        return HttpDelete.METHOD_NAME
    }
}
