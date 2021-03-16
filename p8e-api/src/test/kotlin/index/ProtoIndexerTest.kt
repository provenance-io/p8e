package index

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.io.BaseEncoding
import com.nhaarman.mockitokotlin2.doReturn
import helper.TestUtils
import io.p8e.definition.DefinitionService
import io.p8e.proto.ContractScope
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.Scope
import io.p8e.util.base64Decode
import io.p8e.util.base64Encode
import io.p8e.util.toByteString
import io.p8e.util.toHex
import io.p8e.util.toProtoUuidProv
import io.p8e.util.toPublicKeyProto
import io.provenance.engine.index.ProtoIndexer
import io.provenance.os.client.OsClient
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.os.domain.inputstream.SignatureInputStream
import io.provenance.os.util.CertificateUtil
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.shared.domain.ScopeRecord
import io.provenance.p8e.shared.domain.ScopeTable
import io.provenance.proto.encryption.EncryptionProtos
import io.provenance.proto.encryption.EncryptionProtos.Audience
import io.provenance.proto.encryption.EncryptionProtos.ContextType.TRANSFER
import io.provenance.proto.encryption.EncryptionProtos.ContextType.UNKNOWN
import io.provenance.proto.encryption.EncryptionProtos.Payload
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito
import java.io.InputStream
import java.nio.charset.Charset
import java.security.KeyPair
import java.util.UUID

class ProtoIndexerTest {

    lateinit var scope: Scope

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
    @Ignore
    fun `Validate index fields`(){
        //Setup
        val keyPairs = mapOf(key.public.toHex() to key)

       val dime = EncryptionProtos.DIME.newBuilder()
            .setUuid(UUID.randomUUID().toProtoUuidProv())
            .setOwner(
                Audience.newBuilder()
                    .setPayloadId(123)
                    .setPublicKey(key.public.toHex().toByteString())
                    .setContext(UNKNOWN)
                    .build()
            )
            .putMetadata("key", "metadata")
            .addAllAudience(
                mutableListOf(
                    Audience.newBuilder()
                        .setContext(TRANSFER)
                        .setEncryptedDek("something".toByteString())
                        .setEphemeralPubkey(BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(key.public)).toByteString())
                        .setPayloadId(1234)
                        .setPublicKey(BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(key.public)).toByteString())
                        .build()
                )
            )
            .addAllPayload(
                mutableListOf(
                    Payload.newBuilder()
                        .setId(123)
                        .setCipherText("some-text".toByteString())
                        .build()
                )
            )
            .build()


        //dimeInputStream.getDecryptedPayload(encryptionKeyPair)


        //dimeInputStream.dime
        //Mockito.`when`(dimeInputStream.dime).thenReturn(dime)
        //doReturn(dime).`when`(dimeInputStream).dime
        //Mockito.`when`(osClient.get(envelope.scope.recordGroupList[0].specification.base64Decode(), key.public)).thenReturn(dimeInputStream)


//        signatureInputStream = SignatureInputStream(
//            InputStream.nullInputStream(),
//
//        )

        dimeInputStream = DIMEInputStream(
            dime = dime,
            `in` = InputStream.nullInputStream()
        )

        Mockito.`when`(osClient.get(envelope.scope.recordGroupList[0].specification.base64Decode(), key.public)).thenReturn(dimeInputStream)
       // Mockito.`when`(dimeInputStream.getDecryptedPayload(key)).thenReturn(signatureInputStream)

        //Execute
        val result = protoIndexer.indexFields(envelope.scope, keyPairs)

        //Validate
        println()
    }
}
