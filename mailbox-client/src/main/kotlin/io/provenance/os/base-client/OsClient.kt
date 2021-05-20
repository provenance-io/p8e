package io.provenance.os.baseclient.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl
import io.p8e.crypto.sign
import io.provenance.p8e.encryption.dime.ProvenanceDIME
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.os.util.CertificateUtil
import io.provenance.os.domain.CONTENT_LENGTH_HEADER
import io.provenance.os.domain.OBJECT_BASE_V1
import io.provenance.os.domain.ObjectWithItem
import io.provenance.os.domain.PUBLIC_KEY_BASE_V1
import io.provenance.os.domain.PublicKeyRequest
import io.provenance.os.domain.SIGNATURE_PUBLIC_KEY_FIELD_NAME
import io.provenance.os.domain.Sha512ObjectRequest
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.util.base64Decode
import io.provenance.os.util.orThrow
import io.provenance.proto.encryption.EncryptionProtos.ContextType.RETRIEVAL
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.security.PublicKey
import java.util.UUID


class OsClientProperties(
    val url: String,
    val apiKey: String? = null
) {
    companion object {
        fun fromEnv(): OsClientProperties {
            val url = System.getenv("DOS_URL")
                .orThrow { IllegalStateException("DOS_URL must be specified.") }

            val apiKey = System.getenv("PROVENANCE_API_KEY")
                .orThrow { IllegalStateException("PROVENANCE_API_KEY must be specified.") }

            return OsClientProperties(url, apiKey)
        }
    }
}

open class OsClient(
    objectMapper: ObjectMapper,
    properties: OsClientProperties = OsClientProperties.fromEnv(),
    poolLambda: (PoolingHttpClientConnectionManager) -> Unit = {},
    connectionLambda: (HttpClientBuilder) -> Unit = {},
    requestLambda: (RequestConfig.Builder) -> Unit = {}
): IOsClient, BaseClient(objectMapper, poolLambda, connectionLambda, requestLambda) {
    private val apiKey = properties.apiKey ?: ""
    private val osUrl = properties.url

    companion object {
        val CONTEXT = "/object-store"
    }

    override fun get(
        uri: String,
        publicKey: PublicKey
    ): DIMEInputStream {
        if (uri.isEmpty()) {
            throw IllegalStateException("Empty uri passed.")
        }
        val u = URI(uri)
        if (u.scheme != "object") {
            throw IllegalStateException("Unable to retrieve object for URI with scheme ${u.scheme}")
        }
        return get<InputStream>(
            "${u.toOsUrl()}$OBJECT_BASE_V1/sha512",
            body = Sha512ObjectRequest(
                u.path.substring(1).base64Decode(),
                ECUtils.convertPublicKeyToBytes(publicKey)
            )
        ).let { DIMEInputStream.parse(it) }
    }

    override fun get(
        sha512: ByteArray,
        publicKey: PublicKey
    ): DIMEInputStream {
        if (sha512.size != 64) {
            throw IllegalStateException("Provided SHA-512 must be byte array of size 64, found size: ${sha512.size}")
        }
        return get<InputStream>(
            "$osUrl$OBJECT_BASE_V1/sha512",
            headers = mapOf(Pair("apikey", apiKey)),
            body = Sha512ObjectRequest(sha512, ECUtils.convertPublicKeyToBytes(publicKey))
        ).let { DIMEInputStream.parse(it) }
    }

    override fun put(
        message: Message,
        ownerPublicKey: PublicKey,
        signer: SignerImpl,
        additionalAudiences: Set<PublicKey>,
        metadata: Map<String, String>,
        uuid: UUID
    ): ObjectWithItem {
        return message.toByteArray()
            .let { bytes ->
                put(
                    ByteArrayInputStream(bytes),
                    ownerPublicKey,
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
        ownerPublicKey: PublicKey,
        signer: SignerImpl,
        contentLength: Long,
        additionalAudiences: Set<PublicKey>,
        metadata: Map<String, String>,
        uuid: UUID
    ): ObjectWithItem {
        val signingPublicKey = signer.getPublicKey()
        val signatureInputStream = inputStream.sign(signer)
        val dime = ProvenanceDIME.createDIME(
            payload = signatureInputStream,
            ownerTransactionCert = ownerPublicKey,
            additionalAudience = mapOf(Pair(RETRIEVAL, additionalAudiences)),
            processingAudienceKeys = listOf()
        )

        val dimeInputStream = DIMEInputStream(
            dime.dime,
            dime.encryptedPayload,
            uuid = uuid,
            metadata = metadata + (SIGNATURE_PUBLIC_KEY_FIELD_NAME to CertificateUtil.publicKeyToPem(signingPublicKey)),
            internalHash = true,
            externalHash = false
        )

        return post(
            "$osUrl$OBJECT_BASE_V1",
            dimeInputStream,
            signingPublicKey,
            { signatureInputStream.sign() },
            { dimeInputStream.internalHash() },
            headers = mapOf(
                CONTENT_LENGTH_HEADER to contentLength.toString()
            )
        )
    }

    override fun createPublicKey(
        publicKey: PublicKey
    ): io.provenance.os.domain.PublicKey {
        return post(
            "$osUrl$PUBLIC_KEY_BASE_V1",
            PublicKeyRequest(
                ECUtils.convertPublicKeyToBytes(publicKey)
            )
        )
    }

    override fun deletePublicKey(
        publicKey: PublicKey
    ): io.provenance.os.domain.PublicKey? {
        return delete(
            "$osUrl$PUBLIC_KEY_BASE_V1",
            PublicKeyRequest(
                ECUtils.convertPublicKeyToBytes(publicKey)
            )
        )
    }

    override fun getallKeys(): List<io.provenance.os.domain.PublicKey> {
        return get(
            "$osUrl$PUBLIC_KEY_BASE_V1"
        )
    }

    fun URI.toOsUrl(internal: Boolean = true) = //"http://$host${OsClient.CONTEXT}/${if (internal) "internal" else "secure"}" TODO FIX THIS once we figure out uri stuff
        osUrl
}

