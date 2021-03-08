package io.provenance.os.baseclient.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.provenance.os.baseclient.client.http.ApiException
import io.provenance.os.baseclient.client.http.ByteArrayProviderContentBody
import io.provenance.os.baseclient.client.http.HttpDeleteWithBody
import io.provenance.os.baseclient.client.http.HttpGetWithBody
import io.provenance.os.domain.HASH_FIELD_NAME
import io.provenance.os.domain.SIGNATURE_FIELD_NAME
import io.provenance.os.domain.SIGNATURE_PUBLIC_KEY_FIELD_NAME
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.util.CertificateUtil
import org.apache.commons.logging.LogFactory
import org.apache.commons.logging.impl.Log4JLogger
import org.apache.http.HttpResponse
import org.apache.http.HttpStatus
import org.apache.http.annotation.Contract
import org.apache.http.annotation.ThreadingBehavior
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.protocol.HttpContext
import org.apache.http.util.EntityUtils
import java.io.InputStream
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread

// Implements a spring tomcat strategy that is wrapped around the default apache http keep alive strategy.
// The only difference is that instead of treating the missing header as an indefinite keep alive, we instead
// use 16. This value was chosen based on the spring tomcat default keep alive of 20 seconds. 16 is 80% to be
// conservative and allow for overhead with hop latency. This can be changed in the future if we explicitly
// set keep alive in nginx and spring/tomcat.
// https://github.com/apache/httpcomponents-client/blob/4.5.x/httpclient/src/main/java/org/apache/http/impl/client/DefaultConnectionKeepAliveStrategy.java
@Contract(threading = ThreadingBehavior.IMMUTABLE)
class DefaultSpringTomcatKeepAliveStrategy : DefaultConnectionKeepAliveStrategy() {
    override fun getKeepAliveDuration(response: HttpResponse, context: HttpContext?): Long =
        super.getKeepAliveDuration(response, context)
            .takeIf { it > 0 }
            ?: DEFAULT_VALUE.toLong() * 1000

    companion object {
        val DEFAULT_VALUE = 16
    }
}

open class BaseClient(
    open val objectMapper: ObjectMapper,
    poolLambda: (PoolingHttpClientConnectionManager) -> Unit,
    connectionLambda: (HttpClientBuilder) -> Unit,
    requestLambda: (RequestConfig.Builder) -> Unit
) {
    init {
        val logger = LogFactory.getLog("org.apache.http.wire")
        when (logger) {
            is Log4JLogger -> logger.logger.level = org.apache.log4j.Level.INFO
            is Logger -> logger.level = Level.INFO
        }
    }

    private val client = PoolingHttpClientConnectionManager(DefaultSpringTomcatKeepAliveStrategy.DEFAULT_VALUE.toLong(), TimeUnit.SECONDS)
        .apply {
            maxTotal = 40
            defaultMaxPerRoute = 40
        }.also(poolLambda)
        .let {
            HttpClients.custom()
                .setConnectionManager(it)
                .setRetryHandler(DefaultHttpRequestRetryHandler(3, false))
                .setKeepAliveStrategy(DefaultSpringTomcatKeepAliveStrategy())
                .setDefaultRequestConfig(
                    RequestConfig.custom()
                        .setConnectTimeout(30 * 1000)
                        .setSocketTimeout(60 * 1000)
                        .setConnectionRequestTimeout(10 * 1000)
                        .also(requestLambda)
                        .build()
                )
                .also(connectionLambda)
                .build()
        }.also {
            Runtime.getRuntime().addShutdownHook(thread(start = false) {
                it.close()
            })
        }

    protected inline fun <reified T : Any> get(
        url: String,
        headers: Map<String, String> = mapOf(),
        body: Any? = null
    ): T = _get(
        url,
        headers,
        body
    ).let(this::coerceType)

    protected inline fun <reified T : Any> post(
        url: String,
        body: DIMEInputStream,
        signaturePublicKey: PublicKey,
        noinline signatureProvider: () -> ByteArray,
        noinline hashProvider: () -> ByteArray,
        headers: Map<String, String> = mapOf()
    ): T = _post(
        url,
        body,
        signaturePublicKey,
        signatureProvider,
        hashProvider,
        headers
    ).let(this::coerceType)

    protected inline fun <reified T : Any, R: Any> post(
        url: String,
        body: R,
        headers: Map<String, String> = mapOf()
    ): T = _post(
        url,
        body,
        headers
    ).let(this::coerceType)

    protected inline fun <reified T : Any, R: Any> put(
        url: String,
        body: R,
        headers: Map<String, String> = mapOf()
    ): T = _put(
        url,
        body,
        headers
    ).let(this::coerceType)

    protected inline fun <reified T : Any, R: Any> delete(
        url: String,
        body: R,
        headers: Map<String, String> = mapOf()
    ): T = _delete(
        url,
        body,
        headers
    ).let(this::coerceType)

    protected fun _get(
        url: String,
        headers: Map<String, String> = mapOf(),
        body: Any? = null
    ) = HttpGetWithBody(url)
        .apply {
            headers.forEach {
                this.addHeader(it.key, it.value)
            }
            this.entity = StringEntity(
                objectMapper.writeValueAsString(body),
                ContentType.APPLICATION_JSON
            )
        }.let {
            client.execute(it, HttpClientContext.create())
        }.let(this::handleResponse)

    protected fun _getRaw(
        url: String,
        headers: Map<String, String> = mapOf(),
        body: Any? = null
    ) = HttpGetWithBody(url)
        .apply {
            headers.forEach {
                this.addHeader(it.key, it.value)
            }
            this.entity = StringEntity(
                objectMapper.writeValueAsString(body),
                ContentType.APPLICATION_JSON
            )
        }.let {
            client.execute(it, HttpClientContext.create())
        }

    protected fun _post(
        url: String,
        body: DIMEInputStream,
        signaturePublicKey: PublicKey,
        signatureProvider: () -> ByteArray,
        hashProvider: () -> ByteArray,
        headers: Map<String, String> = mapOf(),
        unencryptedSha512: ByteArray? = null
    ) = HttpPost(url)
        .apply {
            val hashContentBody = ByteArrayProviderContentBody(
                HASH_FIELD_NAME,
                hashProvider
            )
            val signaturePublicKeyBody = ByteArrayProviderContentBody(
                SIGNATURE_PUBLIC_KEY_FIELD_NAME
            ) { CertificateUtil.publicKeyToPem(signaturePublicKey).toByteArray(Charsets.UTF_8) }

            val signatureContentBody = ByteArrayProviderContentBody(
                SIGNATURE_FIELD_NAME,
                signatureProvider
            )
            entity = MultipartEntityBuilder
                .create()
                .addBinaryBody(
                    DIMEInputStream.FIELDNAME,
                    body,
                    ContentType.parse(DIMEInputStream.DEFAULT_CONTENT_TYPE),
                    UUID.randomUUID().toString()
                )
                .addPart(hashContentBody.fieldName, hashContentBody)
                .addPart(signaturePublicKeyBody.fieldName, signaturePublicKeyBody)
                .addPart(signatureContentBody.fieldName, signatureContentBody)
                .build()
            headers.forEach {
                this.addHeader(it.key, it.value)
            }
        }.let { client.execute(it, HttpClientContext.create()) }
        .let(this::handleResponse)

    protected fun <T: Any> _post(
        url: String,
        body: T,
        headers: Map<String, String> = mapOf()
    ) = HttpPost(url)
        .apply {
            entity = StringEntity(
                objectMapper.writeValueAsString(body),
                ContentType.APPLICATION_JSON
            )
            headers.forEach {
                this.addHeader(it.key, it.value)
            }
        }.let { client.execute(it, HttpClientContext.create()) }
        .let(this::handleResponse)

    protected fun <T: Any> _put(
        url: String,
        body: T,
        headers: Map<String, String> = mapOf()
    ) = HttpPut(url)
        .apply {
            entity = StringEntity(
                objectMapper.writeValueAsString(body),
                ContentType.APPLICATION_JSON
            )
            headers.forEach {
                this.addHeader(it.key, it.value)
            }
        }.let { client.execute(it, HttpClientContext.create()) }
        .let(this::handleResponse)

    protected fun <T: Any> _delete(
        url: String,
        body: T,
        headers: Map<String, String> = mapOf()
    ) = HttpDeleteWithBody(url)
        .apply {
            entity = StringEntity(
                objectMapper.writeValueAsString(body),
                ContentType.APPLICATION_JSON
            )
            headers.forEach {
                this.addHeader(it.key, it.value)
            }
        }.let { client.execute(it, HttpClientContext.create()) }
        .let(this::handleResponse)

    protected fun handleResponse(
        response: CloseableHttpResponse
    ) = when (response.statusLine.statusCode) {
        HttpStatus.SC_OK -> response.entity.content
        else -> throw ApiException(
            response.statusLine.statusCode,
            "Status Code: ${response.statusLine.statusCode}\n\nResponse: ${EntityUtils.toString(response.entity)}"
        )
    }

    protected inline fun <reified T : Any> coerceType(
        inputStream: InputStream
    ): T = if (T::class.java.isInstance(inputStream)) {
        inputStream as T
    } else {
        inputStream.use { objectMapper.readValue<T>(it) }
    }
}
