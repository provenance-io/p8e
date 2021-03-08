package io.provenance.p8e.webservice.controller

import io.provenance.p8e.webservice.identity.domain.AccessTokenRequest
import io.provenance.p8e.webservice.identity.domain.AccessTokenResponse
import io.p8e.util.orThrow
import io.provenance.p8e.webservice.config.OAuthException
import io.provenance.p8e.webservice.config.ProvenanceOAuthProperties
import io.provenance.p8e.webservice.identity.ExternalIdentityClient
import io.provenance.p8e.webservice.service.AuthenticationService
import org.redisson.api.RedissonClient
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RedisKey(val value: String)
data class OAuthUrlResponse(val url: String)

fun UUID.toRedisKey() = RedisKey(value = "p8e-api:oauth:$this")

@RestController
@CrossOrigin(origins = ["http://localhost:3000"], allowCredentials = "true")
@RequestMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
open class OAuthController(
    private val provenanceOAuthProperties: ProvenanceOAuthProperties,
    private val identityClient: ExternalIdentityClient,
    private val redissonClient: RedissonClient,
    private val authenticationService: AuthenticationService
) {

    @GetMapping("/external/api/v1/provenance/oauth")
    fun oauth(): OAuthUrlResponse {
        val state = UUID.randomUUID()
        val url = ExternalIdentityClient.Helper.buildOAuthUri(
            url = provenanceOAuthProperties.url,
            clientId = provenanceOAuthProperties.clientId,
            redirectUri = provenanceOAuthProperties.redirectUrl,
            state = state.toString(),
            scopes = listOf(ExternalIdentityClient.MethodScope.GET_IDENTITY.scope)
        )

        redissonClient.getBucket<Boolean>(state.toRedisKey().value)
            .set(false, provenanceOAuthProperties.ttlSeconds.toLong(), TimeUnit.SECONDS)

        return OAuthUrlResponse(url)
    }

    @GetMapping("/external/api/v1/provenance/oauth/callback")
    fun callback(
        @RequestParam("code") code: String,
        @RequestParam("state") state: UUID
    ): AccessTokenResponse {
        redissonClient.getBucket<Boolean>(state.toRedisKey().value)
            .takeIf { it.isExists && it.get() == false }
            ?.set(true)
            .orThrow { OAuthException("state is expired or was previously redeemed") }

        return identityClient.getAccessToken(
            AccessTokenRequest(
                clientId = provenanceOAuthProperties.clientId,
                clientSecret = provenanceOAuthProperties.clientSecret,
                redirectUri = provenanceOAuthProperties.redirectUrl,
                code = code,
                state = state.toString()
            )
        ).let {
            AccessTokenResponse(
                authenticationService.authenticate(it.accessToken).token,
                it.cookieName
            )
        }
    }
}
