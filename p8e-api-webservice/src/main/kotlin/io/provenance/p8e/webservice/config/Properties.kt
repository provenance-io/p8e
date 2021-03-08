package io.provenance.p8e.webservice.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import javax.validation.constraints.NotNull
import javax.validation.constraints.Pattern

@ConfigurationProperties(prefix = "database")
@Validated
class DatabaseProperties {
    @NotNull lateinit var name: String
    @NotNull lateinit var username: String
    @NotNull lateinit var password: String
    @NotNull lateinit var host: String
    @NotNull lateinit var port: String
    @NotNull lateinit var schema: String
    @NotNull @Pattern(regexp = "\\d{1,2}") lateinit var connectionPoolSize: String
}

@ConfigurationProperties(prefix = "elasticsearch")
@Validated
class ElasticSearchProperties {
    @NotNull lateinit var host: String
    @NotNull lateinit var port: String
    @NotNull lateinit var prefix: String
    @NotNull lateinit var username: String
    @NotNull lateinit var password: String
}

@ConfigurationProperties(prefix = "objectstore")
@Validated
class ObjectStoreProperties {
    @NotNull lateinit var url: String
    var key: String? = null
}

@ConfigurationProperties(prefix = "service")
class ServiceProperties {
    @NotNull lateinit var name: String
}

@ConfigurationProperties(prefix = "redis")
@Validated
class RedisProperties {
    @NotNull lateinit var host: String
    @NotNull lateinit var port: String
    @NotNull lateinit var connectionPoolSize: String
}

@ConfigurationProperties(prefix = "provenance.oauth")
@Validated
class ProvenanceOAuthProperties {
    @NotNull lateinit var url: String
    @NotNull lateinit var clientId: String
    @NotNull lateinit var clientSecret: String
    @NotNull lateinit var redirectUrl: String
    @NotNull lateinit var identityUrl: String
    @NotNull lateinit var ttlSeconds: Integer
}