package io.provenance.p8e.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import javax.validation.constraints.NotNull

@ConfigurationProperties(prefix = "jwt")
class JwtProperties {
    @NotNull
    lateinit var secret: String
    @NotNull
    lateinit var expireSeconds: String
    @NotNull
    lateinit var issuer: String
    @NotNull
    lateinit var consumer: String
}

@ConfigurationProperties(prefix = "provenance.keystone")
@Validated
class ProvenanceKeystoneProperties {
    @NotNull lateinit var url: String
}

@ConfigurationProperties(prefix = "smartkey")
class SmartKeyProperties {
    var apiKey: String? = null
}
