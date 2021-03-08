package io.provenance.p8e.webservice.identity.domain

import com.fasterxml.jackson.annotation.JsonProperty

data class AccessTokenRequest(
    @get:JsonProperty("client_id") val clientId: String,
    @get:JsonProperty("client_secret") val clientSecret: String,
    @get:JsonProperty("redirect_uri") val redirectUri: String,
    val code: String,
    val state: String
)

data class AccessTokenResponse(
    @get:JsonProperty("access_token") val accessToken: String,
    @get:JsonProperty("cookie_name") val cookieName: String
)
