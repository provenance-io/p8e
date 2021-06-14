package io.provenance.p8e.webservice.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import io.p8e.util.configureProvenance
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.config.ChaincodeProperties
import io.provenance.p8e.shared.config.JwtProperties
import io.provenance.p8e.shared.config.ProvenanceKeystoneProperties
import io.provenance.p8e.shared.crypto.Account
import io.provenance.p8e.shared.service.KeystoneService
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.client.RestHighLevelClient
import org.kethereum.bip39.model.MnemonicWords
import org.kethereum.bip39.toSeed
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.net.URI

@Configuration
@EnableCaching
@EnableConfigurationProperties(value = [
    ChaincodeProperties::class,
    ElasticSearchProperties::class,
    ObjectStoreProperties::class,
    RedisProperties::class,
    ServiceProperties::class,
    JwtProperties::class,
    ProvenanceKeystoneProperties::class,
])
class AppConfig : WebMvcConfigurer {
    @Bean
    fun accountProvider(chaincodeProperties: ChaincodeProperties): Account {
        return Account(
            seed = MnemonicWords(chaincodeProperties.mnemonic).toSeed(),
            pathString = if (chaincodeProperties.mainNet) Account.PROVENANCE_MAINNET_BIP44_PATH else Account.PROVENANCE_TESTNET_BIP44_PATH,
            mainnet = chaincodeProperties.mainNet
        ).childAccount(hardenAddress = false)
    }

    @Bean
    fun objectMapper(): ObjectMapper {
        val module = SimpleModule()
        return ObjectMapper().configureProvenance()
            .registerModule(module)
    }

    @Bean
    fun osClient(objectMapper: ObjectMapper, objectStoreProperties: ObjectStoreProperties): OsClient =
        OsClient(URI(objectStoreProperties.url))

    @Bean
    fun requestLoggingFilter() = AppRequestLoggingFilter()

    @Bean
    fun redissonClient(redisProperties: RedisProperties, serviceProperties: ServiceProperties): RedissonClient =
        Config()
            .apply {
                useSingleServer()
                    .setAddress("redis://${redisProperties.host}:${redisProperties.port}")
                    .setConnectionPoolSize(redisProperties.connectionPoolSize.toInt())
                    .setPingConnectionInterval(5000)
                    .dnsMonitoringInterval = -1
            }
            .let(Redisson::create)

    @Bean
    fun keystoneService(objectMapper: ObjectMapper, keystoneProperties: ProvenanceKeystoneProperties) = KeystoneService(objectMapper, keystoneProperties.url)

    fun elasticSearchClient(restClientBuilder: RestClientBuilder): RestHighLevelClient {
        return RestHighLevelClient(restClientBuilder)
    }

    @Bean
    fun elasticSearchRestClientBuilder(elasticSearchProperties: ElasticSearchProperties): RestClientBuilder {
        return RestClient.builder(
            HttpHost(
                elasticSearchProperties.host,
                elasticSearchProperties.port.toInt(),
                elasticSearchProperties.prefix
            )
        ).setHttpClientConfigCallback {
            it.setDefaultCredentialsProvider(
                BasicCredentialsProvider().apply {
                    setCredentials(
                        AuthScope.ANY,
                        UsernamePasswordCredentials(
                            elasticSearchProperties.username,
                            elasticSearchProperties.password
                        )
                    )
                }
            )
        }
    }
}
