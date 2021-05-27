package io.provenance.p8e.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import javax.validation.constraints.NotNull

@ConfigurationProperties(prefix = "chaincode")
class ChaincodeProperties {
    @NotNull lateinit var grpcUrl: String
    @NotNull lateinit var url: String
    @NotNull lateinit var apiKey: String
    @NotNull lateinit var mnemonic: String
    @NotNull lateinit var chainId: String
    @NotNull var mainNet: Boolean = false
    @NotNull var emptyIterationBackoffMS: Int = 1_000
    @NotNull var txBatchSize: Int = 25
}

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

@ConfigurationProperties(prefix = "objectstore.locator")
@Validated
class ObjectStoreLocatorProperties {
    var url: String? = null
}
