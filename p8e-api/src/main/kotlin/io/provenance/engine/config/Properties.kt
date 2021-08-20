package io.provenance.engine.config

import io.provenance.engine.batch.fixedSizeThreadPool
import io.provenance.engine.batch.shutdownHook
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
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

@ConfigurationProperties(prefix = "objectstore")
@Validated
class ObjectStoreProperties {
    @NotNull lateinit var url: String
    var key: String? = null
}

@ConfigurationProperties(prefix = "event.stream")
@Validated
class EventStreamProperties {
    @NotNull lateinit var id: String
    @NotNull lateinit var websocketUri: String
    @NotNull lateinit var rpcUri: String
    @NotNull lateinit var epoch: String
    @NotNull lateinit var key: String
}

@ConfigurationProperties(prefix = "service")
class ServiceProperties {
    @NotNull lateinit var name: String
}

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
    @NotNull var gasMultiplier: Double = 1.0
    @NotNull var maxGasMultiplierPerDay: Int = 1000
    @NotNull var blockHeightTimeoutInterval: Int = 12
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

@ConfigurationProperties(prefix = "reaper")
@Validated
class ReaperProperties {
    @NotNull @Pattern(regexp = "\\d{1,2}") lateinit var schedulerPoolSize: String
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

abstract class BaseReaperProperties {
    @NotNull @Pattern(regexp = "\\d+") lateinit var delay: String
    @NotNull @Pattern(regexp = "\\d+") lateinit var interval: String
    @Pattern(regexp = "\\d{1,2}") var poolSize: String? = null

    fun toThreadPool() = poolSize?.toInt()?.let { fixedSizeThreadPool(javaClass.name, it) } ?: fixedSizeThreadPool(javaClass.name)
        .also {
            shutdownHook {
                it.shutdownNow()
                it.awaitTermination(Duration.of(1L, ChronoUnit.DAYS).toMillis(), TimeUnit.MILLISECONDS)
            }
        }
}

@ConfigurationProperties(prefix = "reaper.chaincode")
class ReaperChaincodeProperties : BaseReaperProperties()

@ConfigurationProperties(prefix = "reaper.expiration")
class ReaperExpirationProperties : BaseReaperProperties()

@ConfigurationProperties(prefix = "reaper.fragment")
class ReaperFragmentProperties : BaseReaperProperties()

@ConfigurationProperties(prefix = "reaper.inbox")
class ReaperInboxProperties : BaseReaperProperties()

@ConfigurationProperties(prefix = "reaper.outbox")
class ReaperOutboxProperties : BaseReaperProperties()

@ConfigurationProperties(prefix = "metrics")
class MetricsProperties {
    var enabled: Boolean = true
    var collectors: String = ""
    var host: String = ""
    var tags: String = ""
    var prefix: String = "p8e_api"
}
