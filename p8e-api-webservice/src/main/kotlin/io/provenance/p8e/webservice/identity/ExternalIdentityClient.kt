package io.provenance.p8e.webservice.identity

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.provenance.p8e.webservice.identity.domain.AccessTokenRequest
import io.provenance.p8e.webservice.identity.domain.AccessTokenResponse
import io.provenance.p8e.webservice.identity.web.ACCESS_TOKEN
import io.provenance.p8e.webservice.identity.web.BASE_V1
import io.provenance.p8e.webservice.identity.web.OAUTH2
import feign.Feign
import feign.Headers
import feign.RequestLine
import feign.codec.Decoder
import feign.codec.Encoder
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import java.net.URI
import java.net.URLEncoder

interface ExternalIdentityClient {
    @Headers("Content-Type: application/json")
    @RequestLine("GET $BASE_V1$OAUTH2$ACCESS_TOKEN")
    fun getAccessToken(
        accessTokenRequest: AccessTokenRequest
    ): AccessTokenResponse

    class Builder(
        private val url: String,
        private val encoder: Encoder = JacksonEncoder(listOf(KotlinModule(), JavaTimeModule())),
        private val decoder: Decoder = IdentityDecoder(JacksonDecoder(listOf(KotlinModule(), JavaTimeModule())))
    ) {
        fun build() = Feign.builder()
            .encoder(encoder)
            .decoder(decoder)
            .target(ExternalIdentityClient::class.java, url)
    }

    object Helper {
        fun buildOAuthUri(
            url: String,
            clientId: String,
            redirectUri: String,
            state: String,
            scopes: List<String> = listOf()
        ): String {
            val uri = URI(url)

            val params = mapOf(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "scope" to scopes.joinToString(","),
                "state" to state,
                "response_type" to "code"
            )

            val reduced = params.toList()
                .map { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}" }
                .joinToString("&")

            val query = if (uri.query == null) {
                reduced
            } else {
                val fixedQuery = uri.query
                    .split("=")
                    .let { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}" }
                "$fixedQuery&$reduced"
            }
            return "${uri.scheme}://${uri.authority}${uri.path}?$query${uri.fragment?.let { "#$it" } ?: ""}"
        }
    }

    enum class MethodScope(
        val scope: String
    ) {
        GET_IDENTITY("identity.read-only")
    }
}
