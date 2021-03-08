package io.provenance.os.baseclient.client.http

class ApiException(val statusCode: Int, override val message: String) : RuntimeException(message)
