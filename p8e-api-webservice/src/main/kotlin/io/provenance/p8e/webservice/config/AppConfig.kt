package io.provenance.p8e.webservice.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fortanix.sdkms.v1.ApiClient
import com.fortanix.sdkms.v1.api.AuthenticationApi
import com.fortanix.sdkms.v1.api.SecurityObjectsApi
import com.fortanix.sdkms.v1.auth.ApiKeyAuth
import feign.Feign
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import io.p8e.crypto.SmartKeySigner
import io.p8e.util.configureProvenance
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.config.JwtProperties
import io.provenance.p8e.shared.config.ProvenanceKeystoneProperties
import io.provenance.p8e.shared.config.SmartKeyProperties
import io.provenance.p8e.shared.service.KeystoneService
import io.provenance.p8e.shared.util.IdentityClaims
import io.provenance.p8e.shared.util.TokenManager
import io.provenance.p8e.webservice.identity.ExternalIdentityClient
import io.provenance.p8e.webservice.identity.IdentityDecoder
import io.provenance.p8e.webservice.interceptors.JWTInterceptor
import io.provenance.p8e.webservice.service.KeyManagementService
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.client.RestHighLevelClient
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.net.URI

@Configuration
@EnableCaching
@EnableConfigurationProperties(value = [
    ElasticSearchProperties::class,
    ObjectStoreProperties::class,
    RedisProperties::class,
    ServiceProperties::class,
    ProvenanceOAuthProperties::class,
    JwtProperties::class,
    ProvenanceKeystoneProperties::class,
    SmartKeyProperties::class,
])
class AppConfig : WebMvcConfigurer {

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
    fun externalIdentityClient(
        provenanceOAuthProperties: ProvenanceOAuthProperties,
        objectMapper: ObjectMapper
    ): ExternalIdentityClient =
        Feign.builder()
            .encoder(JacksonEncoder(objectMapper))
            .decoder(IdentityDecoder(JacksonDecoder(objectMapper)))
            .target(
                ExternalIdentityClient::class.java,
                provenanceOAuthProperties.identityUrl
            )

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
    fun tokenManager(jwtProperties: JwtProperties) = TokenManager<IdentityClaims>(
        jwtProperties,
        IdentityClaims.cloneFromJwt
    )

    @Bean
    fun keystoneService(objectMapper: ObjectMapper, keystoneProperties: ProvenanceKeystoneProperties) = KeystoneService(objectMapper, keystoneProperties.url)

    @Autowired
    lateinit var jwtInterceptor: JWTInterceptor

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(jwtInterceptor).excludePathPatterns("/external/**")
    }

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

    @Bean
    fun securityObjectsApi(smartKeyProperties: SmartKeyProperties): SecurityObjectsApi {
        val client = ApiClient().apply { setBasicAuthString(smartKeyProperties.apiKey) }
        com.fortanix.sdkms.v1.Configuration.setDefaultApiClient(client)

        val authResponse = AuthenticationApi().authorize()
        val auth = client.getAuthentication("bearerToken") as ApiKeyAuth
        auth.apiKey = authResponse.accessToken
        auth.apiKeyPrefix = "Bearer"

        return SecurityObjectsApi()
    }
}
