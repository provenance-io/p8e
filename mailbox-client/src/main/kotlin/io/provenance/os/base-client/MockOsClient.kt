//package io.provenance.os.baseclient.client
//
//import com.google.common.hash.Hashing
//import com.google.common.hash.HashingInputStream
//import com.google.protobuf.Message
//import io.provenance.p8e.encryption.dime.ProvenanceDIME
//import io.provenance.os.domain.Bucket
//import io.provenance.os.domain.Item
//import io.provenance.os.domain.ObjectMetadata
//import io.provenance.os.domain.ObjectWithItem
//import io.provenance.os.domain.Signature
//import io.provenance.os.domain.inputstream.DIMEInputStream
//import io.provenance.os.util.CertificateUtil
//import io.provenance.os.util.base64Decode
//import io.provenance.os.util.base64Encode
//import io.provenance.os.util.orThrowNotFound
//import io.provenance.proto.encryption.EncryptionProtos.ContextType.RETRIEVAL
//import org.apache.commons.io.IOUtils
//import java.io.ByteArrayInputStream
//import java.io.InputStream
//import java.net.URI
//import java.security.KeyPair
//import java.security.PublicKey
//import java.time.OffsetDateTime
//import java.util.UUID
//
//class MockOsClient: IOsClient {
//    private val objectCacheBySha512 = mutableMapOf<String, Pair<ObjectWithItem, ByteArray>>()
//
//    override fun get(uri: String, publicKey: PublicKey): DIMEInputStream {
//        val u = URI(uri)
//        if (u.scheme != "object") {
//            throw IllegalStateException("Unable to retrieve object for URI with scheme ${u.scheme}")
//        }
//        return get(
//            u.path.substring(1).base64Decode(),
//            publicKey
//        )
//    }
//
//    override fun get(sha512: ByteArray, publicKey: PublicKey): DIMEInputStream {
//        return objectCacheBySha512[String(sha512.base64Encode())]
//            .orThrowNotFound("Unable to find object with sha512 ${String(sha512.base64Encode())}")
//            .let { (objWithItem, bytes) ->
//                DIMEInputStream.parse(ByteArrayInputStream(bytes), signatures = objWithItem.obj.signatures)
//            }
//    }
//
//    override fun put(
//        message: Message,
//        ownerPublicKey: PublicKey,
//        signingKeyPair: KeyPair,
//        additionalAudiences: Set<PublicKey>,
//        metadata: Map<String, String>,
//        uuid: UUID
//    ): ObjectWithItem {
//        return message.toByteArray()
//            .let { bytes ->
//                put(
//                    ByteArrayInputStream(bytes),
//                    ownerPublicKey,
//                    signingKeyPair,
//                    bytes.size.toLong(),
//                    additionalAudiences,
//                    metadata,
//                    uuid
//                )
//            }
//    }
//
//    override fun put(
//        inputStream: InputStream,
//        ownerPublicKey: PublicKey,
//        signingKeyPair: KeyPair,
//        contentLength: Long,
//        additionalAudiences: Set<PublicKey>,
//        metadata: Map<String, String>,
//        uuid: UUID
//    ): ObjectWithItem {
//        val hashingInputStream = HashingInputStream(Hashing.sha512(), inputStream)
//        val signatureInputStream = hashingInputStream.sign(signingKeyPair.private)
//        val dime = ProvenanceDIME.createDIME(
//            payload = signatureInputStream,
//            ownerTransactionCert = ownerPublicKey,
//            additionalAudience = mapOf(Pair(RETRIEVAL, additionalAudiences)),
//            processingAudienceKeys = listOf()
//        )
//
//        val dimeInputStream = DIMEInputStream(
//            dime.dime,
//            dime.encryptedPayload,
//            uuid = uuid
//        )
//
//        val uuid = dimeInputStream.uuid
//        val bytes = IOUtils.toByteArray(dimeInputStream)
//        val sha512 = hashingInputStream.hash().asBytes()
//
//        val objectWithItem = ObjectWithItem(
//            io.provenance.os.domain.Object(
//                UUID.randomUUID(),
//                uuid,
//                sha512,
//                listOf(
//                    Signature(
//                        signatureInputStream.sign(),
//                        CertificateUtil.publicKeyToPem(signingKeyPair.public).toByteArray(Charsets.UTF_8)
//                    )
//                ),
//                "object://local/${String(sha512.base64Encode())}",
//                "bucket",
//                "name",
//                ObjectMetadata(
//                    UUID.randomUUID(),
//                    uuid,
//                    sha512,
//                    bytes.size,
//                    "",
//                    OffsetDateTime.now(),
//                    "mock",
//                    null,
//                    null
//                ),
//                OffsetDateTime.now(),
//                null,
//                OffsetDateTime.now(),
//                "mock",
//                null,
//                null
//            ),
//            Item(
//                Bucket("bucket"),
//                "name",
//                bytes.size,
//                mapOf()
//            )
//        )
//
//        objectCacheBySha512[String(sha512.base64Encode())] = objectWithItem to bytes
//        return objectWithItem
//    }
//
//    override fun createPublicKey(publicKey: PublicKey): io.provenance.os.domain.PublicKey {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
//    override fun deletePublicKey(publicKey: PublicKey): io.provenance.os.domain.PublicKey? {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
//    override fun getallKeys(): List<io.provenance.os.domain.PublicKey> {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//}
