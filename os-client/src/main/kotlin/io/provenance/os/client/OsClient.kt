package io.provenance.os.client

import com.google.protobuf.Message
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.p8e.crypto.SignerImpl
import io.p8e.crypto.sign
import io.p8e.util.toByteString
import io.p8e.util.toHex
import io.provenance.p8e.encryption.dime.ProvenanceDIME
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.os.util.CertificateUtil
import io.provenance.os.domain.*
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.proto.BufferedStreamObserver
import io.provenance.os.proto.InputStreamChunkedIterator
import io.provenance.os.proto.MailboxServiceGrpc
import io.provenance.os.proto.Mailboxes
import io.provenance.os.proto.ObjectServiceGrpc
import io.provenance.os.proto.Objects
import io.provenance.os.proto.Objects.Chunk.ImplCase
import io.provenance.os.proto.PublicKeyServiceGrpc
import io.provenance.os.proto.PublicKeys
import io.provenance.os.util.base64Decode
import io.provenance.p8e.encryption.model.KeyRef
import io.provenance.os.util.toHexString
import io.provenance.os.util.toPublicKeyProtoOS
import io.provenance.proto.encryption.EncryptionProtos.ContextType.RETRIEVAL
import objectstore.Util
import org.bouncycastle.asn1.tsp.EncryptionInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class OsClient(
    uri: URI,
    private val deadlineMs: Long
) {

    private val objectAsyncClient: ObjectServiceGrpc.ObjectServiceStub
    private val publicKeyBlockingClient: PublicKeyServiceGrpc.PublicKeyServiceBlockingStub
    private val mailboxBlockingClient: MailboxServiceGrpc.MailboxServiceBlockingStub

    init {
        val channel = ManagedChannelBuilder.forAddress(uri.host, uri.port)
            .also {
                if (uri.scheme == "grpcs") {
                    it.useTransportSecurity()
                } else {
                    it.usePlaintext()
                }
            }
            .idleTimeout(60, TimeUnit.SECONDS)
            .keepAliveTime(10, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .build()

        objectAsyncClient = ObjectServiceGrpc.newStub(channel)
        publicKeyBlockingClient = PublicKeyServiceGrpc.newBlockingStub(channel)
        mailboxBlockingClient = MailboxServiceGrpc.newBlockingStub(channel)
    }

    fun ack(uuid: UUID) {
        val request = Mailboxes.AckRequest.newBuilder()
            .setUuid(Util.UUID.newBuilder().setValue(uuid.toString()).build())
            .build()

        mailboxBlockingClient.ack(request)
    }

    fun mailboxGet(publicKey: PublicKey, maxResults: Int): Sequence<Pair<UUID, DIMEInputStream>> {
        val response = mailboxBlockingClient.get(
            Mailboxes.GetRequest.newBuilder()
                .setPublicKey(ECUtils.convertPublicKeyToBytes(publicKey).toByteString())
                .setMaxResults(maxResults)
                .build()
        )

        return response.asSequence()
            .map {
                val dime = DIMEInputStream.parse(ByteArrayInputStream(it.data.toByteArray()))
                Pair(UUID.fromString(it.uuid.value), dime)
            }
    }

    fun get(uri: String, publicKey: PublicKey): DIMEInputStream {
        if (uri.isEmpty()) {
            throw IllegalArgumentException("Empty uri passed.")
        }
        val u = URI(uri)
        if (u.scheme != "object") {
            throw IllegalArgumentException("Unable to retrieve object for URI with scheme ${u.scheme}")
        }

        return get(u.path.substring(1).base64Decode(), publicKey)
    }

    fun get(sha512: ByteArray, publicKey: PublicKey, deadlineSeconds: Long = 60L): DIMEInputStream {
        if (sha512.size != 64) {
            throw IllegalArgumentException("Provided SHA-512 must be byte array of size 64, found size: ${sha512.size}")
        }

        val finishLatch = CountDownLatch(1)
        var error: Throwable? = null
        val bytes = ByteArrayOutputStream()

        objectAsyncClient.get(
            Objects.HashRequest.newBuilder()
                .setHash(sha512.toByteString())
                .setPublicKey(ECUtils.convertPublicKeyToBytes(publicKey).toByteString())
                .build(),
            BufferedStreamObserver(errorHandler = { error = it; finishLatch.countDown() }) { buffer, startTimeMs ->
                val iterator = buffer.iterator()

                if (!iterator.hasNext()) {
                    throw MalformedStreamException("MultiStream has no header")
                }

                val multiStreamHeader = iterator.next()
                if (!multiStreamHeader.hasMultiStreamHeader() || multiStreamHeader.multiStreamHeader.streamCount != 1) {
                    throw MalformedStreamException("MultiStream must start with header and have only one stream")
                }

                if (!iterator.hasNext()) {
                    throw MalformedStreamException("MultiStream has no streams")
                }

                while (iterator.hasNext()) {
                    val packet = iterator.next()
                    if (!packet.hasChunk()) {
                        throw MalformedStreamException("Data stream must be all chunks")
                    }

                    val chunk = packet.chunk
                    if (!chunk.hasHeader() && bytes.size() == 0) {
                        throw MalformedStreamException("First stream chunk must contain header")
                    }

                    when (chunk.implCase) {
                        ImplCase.DATA -> bytes.write(chunk.data.toByteArray())
                        ImplCase.VALUE -> throw MalformedStreamException("VALUE chunk types are not valid on the receive end")
                        ImplCase.END -> {
                            if (iterator.hasNext()) {
                                throw MalformedStreamException("END chunk must be the last chunk of the stream")
                            } else {
                                // no op since we expect the END chunk to be the last chunk of the stream
                            }
                        }
                        ImplCase.IMPL_NOT_SET -> throw IllegalStateException("No chunk impl set")
                    } as Unit
                }

                finishLatch.countDown()
            }
        )

        if (!finishLatch.await(deadlineSeconds, TimeUnit.SECONDS)) {
            throw TimeoutException("Deadline exceeded waiting for object ${sha512.toHexString()} with public key ${publicKey.toHex()}")
        }
        if (error != null) {
            throw error!!
        }

        return DIMEInputStream.parse(ByteArrayInputStream(bytes.toByteArray()))
    }

    fun put(
        message: Message,
        encryptionPublicKey: PublicKey,
        signer: SignerImpl,
        additionalAudiences: Set<PublicKey> = setOf(),
        metadata: Map<String, String> = mapOf(),
        uuid: UUID = UUID.randomUUID()
    ): Objects.ObjectResponse {
        val bytes = message.toByteArray()

        return put(
            ByteArrayInputStream(bytes),
            encryptionPublicKey,
            signer,
            bytes.size.toLong(),
            additionalAudiences,
            metadata,
            uuid
        )
    }

    fun put(
        inputStream: InputStream,
        encryptionPublicKey: PublicKey,
        signer: SignerImpl,
        contentLength: Long,
        additionalAudiences: Set<PublicKey> = setOf(),
        metadata: Map<String, String> = mapOf(),
        uuid: UUID = UUID.randomUUID(),
        deadlineSeconds: Long = 60L
    ): Objects.ObjectResponse {
        val signerPublicKey = signer.getPublicKey()
        val signatureInputStream = inputStream.sign(signer)
        val signingPublicKey = CertificateUtil.publicKeyToPem(signerPublicKey)

        val dime = ProvenanceDIME.createDIME(
            payload = signatureInputStream,
            ownerEncryptionPublicKey = encryptionPublicKey,
            additionalAudience = mapOf(Pair(RETRIEVAL, additionalAudiences)),
            processingAudienceKeys = listOf()
        )
        val dimeInputStream = DIMEInputStream(
            dime.dime,
            dime.encryptedPayload,
            uuid = uuid,
            metadata = metadata + (SIGNATURE_PUBLIC_KEY_FIELD_NAME to CertificateUtil.publicKeyToPem(signerPublicKey)),
            internalHash = true,
            externalHash = false
        )
        val responseObserver = SingleResponseObserver<Objects.ObjectResponse>()
        val requestObserver = objectAsyncClient.put(responseObserver)
        val header = Objects.MultiStreamHeader.newBuilder()
            .setStreamCount(1)
            .putMetadata(CREATED_BY_HEADER, UUID(0, 0).toString())
        .build()

        dimeInputStream.use {
            try {
                requestObserver.onNext(Objects.ChunkBidi.newBuilder().setMultiStreamHeader(header).build())

                val iterator = InputStreamChunkedIterator(it, DIME_FIELD_NAME, contentLength)
                while (iterator.hasNext()) {
                    requestObserver.onNext(iterator.next())
                }

                requestObserver.onNext(propertyChunkRequest(HASH_FIELD_NAME to dimeInputStream.internalHash()))
                requestObserver.onNext(propertyChunkRequest(SIGNATURE_FIELD_NAME to signatureInputStream.sign()))
                requestObserver.onNext(propertyChunkRequest(SIGNATURE_PUBLIC_KEY_FIELD_NAME to signingPublicKey.toByteArray(Charsets.UTF_8)))

                requestObserver.onCompleted()
            } catch (t: Throwable) {
                requestObserver.onError(t)
                throw t
            }
        }

        if (!responseObserver.finishLatch.await(deadlineSeconds, TimeUnit.SECONDS)) {
            throw TimeoutException("No response received")
        }
        if (responseObserver.error != null) {
            throw responseObserver.error!!
        }

        return responseObserver.get()
    }

    fun createPublicKey(publicKey: PublicKey): PublicKeys.PublicKeyResponse? =
        publicKeyBlockingClient.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .add(
                PublicKeys.PublicKeyRequest.newBuilder()
                    .setPublicKey(publicKey.toPublicKeyProtoOS())
                    .setUrl("http://localhost") // todo: what is this supposed to be?
                    .build()
            )
}

class SingleResponseObserver<T> : StreamObserver<T> {
    val finishLatch: CountDownLatch = CountDownLatch(1)
    var error: Throwable? = null
    private var item: T? = null

    fun get(): T = item ?: throw IllegalStateException("Attempting to get result before it was received")

    override fun onNext(item: T) {
        this.item = item
    }

    override fun onError(t: Throwable) {
        error = t
        finishLatch.countDown()
    }

    override fun onCompleted() {
        finishLatch.countDown()
    }
}

fun propertyChunkRequest(pair: Pair<String, ByteArray>): Objects.ChunkBidi =
    Objects.ChunkBidi.newBuilder()
        .setChunk(
            Objects.Chunk.newBuilder()
                .setHeader(
                    Objects.StreamHeader.newBuilder()
                        .setName(pair.first)
                        .build()
                )
                .setValue(pair.second.toByteString())
                .build()
        )
        .build()
