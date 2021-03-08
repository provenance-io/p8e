package io.provenance.p8e.webservice.identity

import feign.codec.Decoder
import feign.Response
import java.util.UUID
import java.lang.reflect.Type

class IdentityDecoder(private val delegate: Decoder) : Decoder {
    override fun decode(
        response: Response?,
        type: Type?
    ): Any? {
        if (response != null &&
            response.status() == 200 &&
            type != null &&
            type is Class<*> &&
            (type == UUID::class.java || type == String::class.java)
        ) {
            return response.body()
                .asInputStream()
                .use {
                    it.readAllBytes()
                }.toString(Charsets.UTF_8)
                .let {
                    when (type) {
                        String::class.java -> it
                        UUID::class.java -> UUID.fromString(it.replace("\"", ""))
                        else -> throw IllegalStateException("Unknown type found: ${type.name}")
                    }
                }
        }
        return delegate.decode(response, type)
    }
}
