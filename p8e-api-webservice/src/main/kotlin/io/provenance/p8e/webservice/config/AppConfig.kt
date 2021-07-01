package io.provenance.p8e.webservice.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fortanix.sdkms.v1.ApiClient
import com.fortanix.sdkms.v1.api.AuthenticationApi
import com.fortanix.sdkms.v1.api.SecurityObjectsApi
import com.fortanix.sdkms.v1.api.SignAndVerifyApi
import com.fortanix.sdkms.v1.auth.ApiKeyAuth
import io.p8e.crypto.SignerFactory
import io.p8e.crypto.SmartKeySigner
import io.p8e.util.configureProvenance
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.config.JwtProperties
import io.provenance.p8e.shared.config.ProvenanceKeystoneProperties
import io.provenance.p8e.shared.config.SmartKeyProperties
import io.provenance.p8e.shared.service.KeystoneService
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.client.RestHighLevelClient
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.net.URI

@Configuration
@EnableCaching
@EnableConfigurationProperties(value = [
    ElasticSearchProperties::class,
    ObjectStoreProperties::class,
    ServiceProperties::class,
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
        OsClient(
            uri = URI(objectStoreProperties.url),
            deadlineMs = 60000
        )

    @Bean
    fun requestLoggingFilter() = AppRequestLoggingFilter()

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

    /**
     * Add support for new key management.
     */
    @Bean
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    fun smartKeyApiClient(smartKeyProperties: SmartKeyProperties): ApiClient? {
        return if(smartKeyProperties.apiKey != null && smartKeyProperties.apiKey != "") {
            ApiClient().apply {
                setBasicAuthString(smartKeyProperties.apiKey)
                com.fortanix.sdkms.v1.Configuration.setDefaultApiClient(this)

                // authenticate with api
                val authResponse = authenticationApi(this).authorize()
                val auth = this.getAuthentication("bearerToken") as ApiKeyAuth
                auth.apiKey = authResponse.accessToken
                auth.apiKeyPrefix = "Bearer"
            }
        } else {
            ApiClient() // SmartKey is not initialized.
        }
    }

    @Bean
    fun authenticationApi(smartKeyApiClient: ApiClient): AuthenticationApi = AuthenticationApi(smartKeyApiClient)

    @Bean
    fun signAndVerifyApi(smartKeyApiClient: ApiClient): SignAndVerifyApi = SignAndVerifyApi(smartKeyApiClient)

    @Bean
    fun securityObjectsApi(smartKeyApiClient: ApiClient): SecurityObjectsApi = SecurityObjectsApi(smartKeyApiClient)

    @Bean
    fun smartKeySigner(signAndVerifyApi: SignAndVerifyApi, securityObjectsApi: SecurityObjectsApi): SmartKeySigner
            = SmartKeySigner(signAndVerifyApi, securityObjectsApi)

    @Bean
    fun signer(smartKeySigner: SmartKeySigner): SignerFactory = SignerFactory(smartKeySigner)
}
