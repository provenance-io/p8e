package io.provenance.os.mailbox.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl
import io.p8e.crypto.sign
import io.provenance.p8e.encryption.dime.ProvenanceDIME
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.os.util.CertificateUtil
import io.provenance.os.baseclient.client.http.ApiException
import io.provenance.os.baseclient.client.BaseClient
import io.provenance.os.domain.AckRequest
import io.provenance.os.domain.CONTENT_LENGTH_HEADER
import io.provenance.os.domain.IsAckedRequest
import io.provenance.os.domain.MAILBOX_BASE_V1
import io.provenance.os.domain.MultiItem
import io.provenance.os.domain.ObjectWithItem
import io.provenance.os.domain.POLL
import io.provenance.os.domain.PollRequest
import io.provenance.os.domain.SCOPE_HEADER
import io.provenance.os.domain.SIGNATURE_PUBLIC_KEY_FIELD_NAME
import io.provenance.os.domain.Scope
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.mailbox.client.inputstream.HeaderInputStream
import io.provenance.os.mailbox.client.iterator.MultiDIMEIterator
import io.provenance.os.util.orThrow
import io.provenance.p8e.encryption.model.KeyRef
import io.provenance.proto.encryption.EncryptionProtos.ContextType.RETRIEVAL
import org.apache.http.HttpStatus
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.util.EntityUtils
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.PublicKey
import java.util.UUID

class MailboxClientProperties(
    val url: String,
    val apiKey: String? = null
) {
    companion object {
        fun fromEnv(): MailboxClientProperties {
            val url = System.getenv("DOS_URL")
                .orThrow { IllegalStateException("DOS_URL must be specified.") }

            val apiKey = System.getenv("PROVENANCE_API_KEY")
                .orThrow { IllegalStateException("PROVENANCE_API_KEY must be specified.") }

            return MailboxClientProperties(url, apiKey)
        }
    }
}

fun String.urlEncode() = URLEncoder.encode(this, Charsets.UTF_8)

class MailboxClient(
    objectMapper: ObjectMapper,
    properties: MailboxClientProperties = MailboxClientProperties.fromEnv(),
    poolLambda: (PoolingHttpClientConnectionManager) -> Unit = {},
    connectionLambda: (HttpClientBuilder) -> Unit = {},
    requestLambda: (RequestConfig.Builder) -> Unit = {}
): BaseClient(objectMapper, poolLambda, connectionLambda, requestLambda), IMailboxClient {
    private val apiKey = properties.apiKey ?: ""
    private val osUrl = properties.url

    companion object {
        const val CONTEXT = "/mailbox"
    }

    override fun ack(
        objectPublicKeyUuid: UUID,
        publicKey: ByteArray
    ): Boolean {
        return put(
            "$osUrl$MAILBOX_BASE_V1/ack",
            AckRequest(
                objectPublicKeyUuid,
                publicKey
            ),
            headers = mapOf(Pair("apikey", apiKey))
        )
    }

    override fun isAcked(
        objectUuid: UUID,
        publicKey: ByteArray
    ): Boolean {
        return get(
            "$osUrl$MAILBOX_BASE_V1/isAck",
            headers = mapOf(Pair("apikey", apiKey)),
            body = IsAckedRequest(
                objectUuid,
                publicKey
            )
        )
    }

    override fun poll(
        publicKey: PublicKey,
        scope: Scope,
        limit: Int
    ): MultiDIMEIterator {
        return _getRaw(
            "$osUrl$MAILBOX_BASE_V1$POLL",
            headers = mapOf(Pair("apikey", apiKey)),
            body = PollRequest(
                ECUtils.convertPublicKeyToBytes(publicKey),
                scope,
                limit
            )
        ).let { response ->
            when (response.statusLine.statusCode) {
                HttpStatus.SC_OK -> responseToMultiDIME(response, ECUtils.convertPublicKeyToBytes(publicKey))
                else -> throw ApiException(
                    response.statusLine.statusCode,
                    "Status Code: ${response.statusLine.statusCode}\n\nResponse: ${EntityUtils.toString(response.entity)}"
                )
            }
        }
    }

    override fun put(
        message: Message,
        ownerEncryptionKeyRef: KeyRef,
        signer: SignerImpl,
        additionalAudiences: Set<PublicKey>,
        metadata: Map<String, String>,
        uuid: UUID
    ): ObjectWithItem {
        return message.toByteArray()
            .let { bytes ->
                put(
                    ByteArrayInputStream(bytes),
                    ownerEncryptionKeyRef,
                    signer,
                    bytes.size.toLong(),
                    additionalAudiences,
                    metadata,
                    uuid
                )
            }
    }

    override fun put(
        inputStream: InputStream,
        ownerEncryptionKeyRef: KeyRef,
        signer: SignerImpl,
        contentLength: Long,
        additionalAudiences: Set<PublicKey>,
        metadata: Map<String, String>,
        uuid: UUID
    ): ObjectWithItem {
        val signingInputStream = inputStream.sign(signer)
        val dime = ProvenanceDIME.createDIME(
            payload = signingInputStream,
            ownerEncryptionKeyRef = ownerEncryptionKeyRef,
            additionalAudience = mapOf(Pair(RETRIEVAL, additionalAudiences)),
            processingAudienceKeys = listOf()
        )

        val dimeInputStream = DIMEInputStream(
            dime.dime,
            dime.encryptedPayload,
            uuid = uuid,
            metadata = metadata + (SIGNATURE_PUBLIC_KEY_FIELD_NAME to CertificateUtil.publicKeyToPem(signer.getPublicKey())),
            internalHash = true,
            externalHash = false
        )

        return put(
            dimeInputStream,
            contentLength,
            signer.getPublicKey(),
            { signingInputStream.sign() },
            { dimeInputStream.internalHash() }
        )
    }

    override fun put(
        dimeInputStream: DIMEInputStream,
        contentLength: Long,
        signingPublicKey: PublicKey,
        signatureProvider: () -> ByteArray,
        hashProvider: () -> ByteArray,
        scope: Scope
    ): ObjectWithItem {
        return post(
            "$osUrl$MAILBOX_BASE_V1",
            dimeInputStream,
            signingPublicKey,
            signatureProvider,
            hashProvider,
            headers = mapOf(
                "apikey" to apiKey,
                CONTENT_LENGTH_HEADER to contentLength.toString(),
                SCOPE_HEADER to scope.name
            )
        )
    }

    private fun responseToMultiDIME(
        response: CloseableHttpResponse,
        publicKey: ByteArray
    ): MultiDIMEIterator {
        val header = HeaderInputStream.parse<MultiItem>(response.entity.content)
        return MultiDIMEIterator(
            this,
            publicKey,
            header.inputStream,
            header.header.sizes,
            header.header.uuids,
            header.header.sha512s,
            header.header.signatures,
            header.header.boundary
        )
    }
}
