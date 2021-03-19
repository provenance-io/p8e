package index

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString
import helper.TestUtils
import io.p8e.definition.DefinitionService
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.Scope
import io.p8e.util.base64Decode
import io.p8e.util.toByteString
import io.p8e.util.toHex
import io.p8e.util.toProtoUuidProv
import io.provenance.engine.index.ProtoIndexer
import io.provenance.os.client.OsClient
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.domain.inputstream.SignatureInputStream
import io.provenance.os.util.CertificateUtil
import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.encryption.ecies.ProvenanceECIESEncrypt
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.p8e.encryption.kdf.ProvenanceHKDFSHA256
import io.provenance.p8e.shared.domain.ScopeRecord
import io.provenance.p8e.shared.domain.ScopeTable
import io.provenance.proto.encryption.EncryptionProtos
import io.provenance.proto.encryption.EncryptionProtos.Audience
import io.provenance.proto.encryption.EncryptionProtos.ContextType.TRANSFER
import io.provenance.proto.encryption.EncryptionProtos.Payload
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.InputStream
import java.security.KeyPair
import java.security.Signature
import java.util.Arrays
import java.util.UUID

class ProtoIndexerTest {

    lateinit var key: KeyPair

    lateinit var objectMapper: ObjectMapper

    lateinit var osClient: OsClient

    lateinit var protoIndexer: ProtoIndexer

    lateinit var envelope: Envelope

    lateinit var scopeRecord: ScopeRecord

    lateinit var definitionService: DefinitionService

    lateinit var dimeInputStream: DIMEInputStream

    lateinit var signatureInputStream: SignatureInputStream

    @Before
    fun setup(){
        TestUtils.DatabaseConnect()

        key = TestUtils.generateKeyPair()

        transaction{
            SchemaUtils.create(ScopeTable)

            scopeRecord = ScopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), ScopeTable)
                publicKey = key.public.toHex()
                scopeUuid = UUID.randomUUID()
                data = Scope.getDefaultInstance()
                lastExecutionUuid = UUID.randomUUID()
            }
        }

        envelope = TestUtils.generateTestEnvelope(key, scopeRecord)

        objectMapper = Mockito.mock(ObjectMapper::class.java)
        osClient = Mockito.mock(OsClient::class.java)
        definitionService = Mockito.mock(DefinitionService::class.java)
        dimeInputStream = Mockito.mock(DIMEInputStream::class.java)
        signatureInputStream = Mockito.mock(SignatureInputStream::class.java)

        protoIndexer = ProtoIndexer(objectMapper, osClient)
    }

    @Test
    fun `Validate index fields`(){
        //Setup
        val keyPairs = mapOf(key.public.toHex() to key)

        // duplicate the payload tag so cryptogram validation passes.
        val encryptor = ProvenanceECIESEncrypt(key.public, "")
        val cryptogram = encryptor.encrypt(BaseEncoding.base64().encode(key.public.encoded).toByteArray(Charsets.UTF_8))
        val secretKey = ProvenanceKeyGenerator.computeSharedKey(key.private, cryptogram.ephemeralPublicKey)
        val emphemeralDerivedKey = ProvenanceHKDFSHA256.derive(ECUtils.convertSharedSecretKeyToBytes(secretKey), null, ECUtils.KDF_SIZE)

        val encBytes = Arrays.copyOf(emphemeralDerivedKey, 32)
        val encKey = ProvenanceAESCrypt.secretKeySpecGenerate(encBytes)
        val macKeyBytes = Arrays.copyOfRange(emphemeralDerivedKey, 32, 64)
        val mac = ProvenanceAESCrypt.encrypt(macKeyBytes, "", encKey, true)!!

        val dime = EncryptionProtos.DIME.newBuilder()
            .setUuid(UUID.randomUUID().toProtoUuidProv())
            .setOwner(
                Audience.newBuilder()
                    .setContext(TRANSFER)
                    .setEncryptedDek(BaseEncoding.base64().encode(cryptogram.encryptedData).toByteString())
                    .setEphemeralPubkey(ByteString.copyFrom(BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(cryptogram.ephemeralPublicKey)).toByteArray(Charsets.UTF_8)))
                    .setPayloadId(1234)
                    .setTag(ByteString.copyFrom(BaseEncoding.base64().encode(mac).toByteArray(Charsets.UTF_8)))
                    .setPublicKey(ByteString.copyFrom(BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(key.public)).toByteArray(Charsets.UTF_8)))
                    .build()
            )
            .putMetadata("key", "metadata")
            .addAllAudience(
                mutableListOf(
                    Audience.newBuilder()
                        .setContext(TRANSFER)
                        .setTag(ByteString.copyFrom(BaseEncoding.base64().encode(mac).toByteArray(Charsets.UTF_8)))
                        .setEncryptedDek(BaseEncoding.base64().encode(cryptogram.encryptedData).toByteString())
                        .setEphemeralPubkey(ByteString.copyFrom(BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(cryptogram.ephemeralPublicKey)).toByteArray(Charsets.UTF_8)))
                        .setPayloadId(1234)
                        .setPublicKey(BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(key.public)).toByteString())
                        .build()
                )
            )
            .addAllPayload(
                mutableListOf(
                    Payload.newBuilder()
                        .setId(1234)
                        .setCipherText("".toByteString())
                        .build()
                )
            )
            .build()

        val signatures = mutableListOf(
            io.provenance.os.domain.Signature(
                envelope.signaturesList[0].signer.signingPublicKey.toByteArray(),
                CertificateUtil.publicKeyToPem(key.public).toByteArray(Charsets.UTF_8)
            )
        )

        //Setup signatures
        val signatureJava = Signature.getInstance("SHA512withECDSA", "BC").apply { initSign(key.private) }.let { it.sign() }

        val verifySignature = Signature.getInstance("SHA512withECDSA", "BC").apply { initVerify(key.public) }

        signatureInputStream = SignatureInputStream(
            InputStream.nullInputStream(),
            verifySignature,
            envelope.signaturesList[0].signer.signingPublicKey.toByteArray()
        )

        dimeInputStream = DIMEInputStream(
            dime = dime,
            `in` = InputStream.nullInputStream(),
            signatures = signatures
        )

        Mockito.`when`(osClient.get(envelope.scope.recordGroupList[0].specification.base64Decode(), key.public)).thenReturn(
            DIMEInputStream(
                dime = dime,
                `in` = InputStream.nullInputStream(),
                signatures = signatures
            )
        )
        Mockito.`when`(dimeInputStream.getDecryptedPayload(key)).thenReturn(signatureInputStream)

        //Execute
        val result = protoIndexer.indexFields(envelope.scope, keyPairs)

        //Validate
        println()
    }
}
