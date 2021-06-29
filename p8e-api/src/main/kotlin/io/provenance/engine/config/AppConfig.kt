package io.provenance.engine.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.timgroup.statsd.NonBlockingStatsDClientBuilder
import com.tinder.scarlet.Scarlet
import com.tinder.scarlet.messageadapter.moshi.MoshiMessageAdapter
import com.tinder.scarlet.streamadapter.rxjava2.RxJava2StreamAdapterFactory
import com.tinder.scarlet.websocket.okhttp.newWebSocketFactory
import feign.Feign
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import io.p8e.util.configureProvenance
import io.provenance.engine.crypto.Account
import io.provenance.engine.domain.RPCClient
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.engine.index.query.Operation
import io.provenance.engine.index.query.OperationDeserializer
import io.provenance.engine.service.*
import io.provenance.p8e.shared.util.KeyClaims
import io.provenance.p8e.shared.util.TokenManager
import io.provenance.p8e.shared.state.EnvelopeStateEngine
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.config.JwtProperties
import io.provenance.p8e.shared.config.ProvenanceKeystoneProperties
import io.provenance.p8e.shared.service.KeystoneService
import okhttp3.OkHttpClient
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.client.RestHighLevelClient
import org.kethereum.bip39.model.MnemonicWords
import org.kethereum.bip39.toSeed
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import p8e.Jobs
import java.lang.IllegalArgumentException
import java.net.URI
import java.time.Duration

@Configuration
@EnableCaching
@EnableConfigurationProperties(value = [
    ChaincodeProperties::class,
    ElasticSearchProperties::class,
    EventStreamProperties::class,
    JwtProperties::class,
    ObjectStoreProperties::class,
    ObjectStoreLocatorProperties::class,
    ReaperChaincodeProperties::class,
    ReaperExpirationProperties::class,
    ReaperFragmentProperties::class,
    ReaperInboxProperties::class,
    ReaperOutboxProperties::class,
    ServiceProperties::class,
    ProvenanceKeystoneProperties::class,
    MetricsProperties::class,
])
class AppConfig : WebMvcConfigurer {

    /**
     * TODO: To be a service account -- below is for testing purposes.
     */
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
        module.addDeserializer(Operation::class.java, OperationDeserializer(Operation::class.java))
        return ObjectMapper().configureProvenance()
            .registerModule(module)
    }

    @Bean
    fun rpcClient(
        objectMapper: ObjectMapper,
        eventStreamProperties: EventStreamProperties
    ): RPCClient {
        return Feign.builder()
            .encoder(JacksonEncoder(objectMapper))
            .decoder(JacksonDecoder(objectMapper))
            .target(RPCClient::class.java, eventStreamProperties.rpcUri)
    }

    @Bean
    fun eventStreamBuilder(eventStreamProperties: EventStreamProperties): Scarlet.Builder {
        val node = URI(eventStreamProperties.websocketUri)
        return Scarlet.Builder()
            .webSocketFactory(
                OkHttpClient.Builder()
                .readTimeout(Duration.ofSeconds(60)) // ~ 12 pbc block cuts
                .build()
                .newWebSocketFactory("${node.scheme}://${node.host}:${node.port}/websocket"))
            .addMessageAdapterFactory(MoshiMessageAdapter.Factory())
            .addStreamAdapterFactory(RxJava2StreamAdapterFactory())
    }

    @Bean
    fun envelopeStateEngine(): EnvelopeStateEngine =
        EnvelopeStateEngine()

    @Bean
    fun protobufHttpMessageConverter(): ProtobufHttpMessageConverter = ProtobufHttpMessageConverter()

    override fun configureMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.add(ProtobufHttpMessageConverter())
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
    fun tokenManager(jwtProperties: JwtProperties) = TokenManager<KeyClaims>(
        jwtProperties,
        KeyClaims.cloneFromJwt
    )

    @Bean
    fun keystoneService(objectMapper: ObjectMapper, keystoneProperties: ProvenanceKeystoneProperties) = KeystoneService(objectMapper, keystoneProperties.url)

    @Bean
    fun jwtServerInterceptor(
        tokenManager: TokenManager<KeyClaims>
    ): JwtServerInterceptor {
        return JwtServerInterceptor(
            tokenManager
        )
    }

    @Bean
    fun unhandledExceptionHandler(): UnhandledExceptionInterceptor {
        return UnhandledExceptionInterceptor()
    }

    @Bean
    fun metricsService(metricsProperties: MetricsProperties): MetricsService = (if (metricsProperties.enabled) metricsProperties.collectors else "")
        .split(',')
        .distinct()
        .filterNot { it.isBlank() }
        .map {
            when (it) {
                "file" -> LogFileMetricCollector(metricsProperties.prefix)
                "datadog" -> DataDogMetricCollector(
                    NonBlockingStatsDClientBuilder()
                        .blocking(false)
                        .prefix(metricsProperties.prefix)
                        .hostname(metricsProperties.host)
                        .timeout(1_000)
                        .enableTelemetry(false)
                        .build())
                else -> throw IllegalArgumentException("Unknown metric collector type $it")
            }
        }.let { collectors ->
            val labels = metricsProperties.tags.split(" ")
                .filterNot { it.isBlank() }
                .map { tag ->
                    val (first, second) = tag.takeIf { it.contains(':') }?.split(":") ?: listOf(tag, "")
                    first to second
                }.toMap()
            MetricsService(collectors, labels)
        }

    @Bean
    fun jobHandlerServiceFactory(osLocatorChaincodeService: OSLocatorChaincodeService,
                                 dataAccessChaincodeService: DataAccessChaincodeService): JobHandlerServiceFactory = { payload ->
        when (payload.jobCase) {
            Jobs.P8eJob.JobCase.ADDAFFILIATEOSLOCATOR -> osLocatorChaincodeService
            Jobs.P8eJob.JobCase.MSGADDSCOPEDATAACCESSREQUEST -> dataAccessChaincodeService
            else -> throw IllegalArgumentException("No handler registered for job of type ${payload.jobCase.name}")
        }
    }
}
