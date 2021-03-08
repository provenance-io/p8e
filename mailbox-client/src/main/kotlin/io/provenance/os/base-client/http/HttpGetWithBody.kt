package io.provenance.os.baseclient.client.http

import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpGet
import java.net.URI

/**
 * Allows sending a GET request with a request body.
 */
class HttpGetWithBody(uri: String) : HttpEntityEnclosingRequestBase() {

    init {
        setURI(URI.create(uri))
    }

    override fun getMethod(): String {
        return HttpGet.METHOD_NAME
    }
}
