package io.provenance.pbc.clients

import com.fasterxml.jackson.databind.ObjectMapper
import feign.Feign
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import org.slf4j.LoggerFactory

class CosmosRemoteInvocationException(val method: String, val httpMethod: String, val url: String, val status: Int, val body: String?)
    : RuntimeException("$method: failed to $httpMethod $url: $status${body?.let { " body:$body" } ?: ""}")

typealias BlockchainClientCfg = (Feign.Builder) -> Unit

class Blockchain(val uri: String, val apiKey: String, val om: ObjectMapper, vararg val configs: BlockchainClientCfg) {
    inline fun <reified T> new(): T = Feign.builder()
            .encoder(JacksonEncoder(om))
            .decoder(JacksonDecoder(om))
            .requestInterceptor { it.header("apikey", apiKey) }
            .errorDecoder { method, r ->
                val log = LoggerFactory.getLogger(Blockchain::class.java)
                val status = r.status()
                val url = r.request().url()
                val httpMethod = r.request().httpMethod().name
                log.warn("$method: $status $httpMethod $url")

                val body = r.body()?.asReader()?.readLines()?.joinToString("\n")
                throw CosmosRemoteInvocationException(method, httpMethod, url, status, body)
            }
            .also { b -> configs.forEach { it.invoke(b) } }
            .target(T::class.java, uri)
}
