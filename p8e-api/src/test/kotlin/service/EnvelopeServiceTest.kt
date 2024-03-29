package service

import helper.TestUtils
import io.p8e.crypto.SignerFactory
import io.p8e.engine.ContractEngine
import io.p8e.proto.ContractScope
import io.p8e.util.*
import io.provenance.engine.service.*
import io.provenance.os.client.OsClient
import io.provenance.os.domain.inputstream.DIMEInputStream
import io.provenance.p8e.encryption.model.KeyProviders.DATABASE
import io.provenance.p8e.shared.domain.*
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.shared.state.EnvelopeStateEngine
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.security.KeyPair
import java.time.OffsetDateTime
import java.util.*

class EnvelopeServiceTest {

    lateinit var envelopeRecord: EnvelopeRecord

    lateinit var scopeRecord: ScopeRecord

    lateinit var envelopeService: EnvelopeService

    lateinit var affiliateService: AffiliateService

    lateinit var osClient: OsClient

    lateinit var mailboxService: MailboxService

    lateinit var envelopeStateEngine: EnvelopeStateEngine

    lateinit var eventService: EventService

    lateinit var metricService: MetricsService

    lateinit var contractEngine: ContractEngine

    lateinit var dimeInputStream: DIMEInputStream

    lateinit var signerFactory: SignerFactory

    val ecKeys: KeyPair = TestUtils.generateKeyPair()

    val signingKeys: KeyPair = TestUtils.generateKeyPair()

    val authKeys: KeyPair = TestUtils.generateKeyPair()

    @Before
    fun setup(){
        TestUtils.DatabaseConnect()

        transaction {
            SchemaUtils.create(EnvelopeTable)
            SchemaUtils.create(ScopeTable)
            SchemaUtils.create(AffiliateTable)

            scopeRecord = ScopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), ScopeTable)
                publicKey = signingKeys.public.toHex()
                scopeUuid = UUID.randomUUID()
                data = ContractScope.Scope.getDefaultInstance()
                lastExecutionUuid = UUID.randomUUID()
            }

            AffiliateTable.insert {
                it[alias] = "alias"
                it[publicKey] = signingKeys.public.toHex()
                it[privateKey] = signingKeys.private.toHex()
                it[whitelistData] = null
                it[encryptionPublicKey] = ecKeys.public.toHex()
                it[encryptionPrivateKey] = ecKeys.private.toHex()
                it[active] = true
                it[indexName] = "scopes"
                it[signingKeyUuid] = UUID.randomUUID()
                it[encryptionKeyUuid] = UUID.randomUUID()
                it[keyType] = DATABASE
                it[authPublicKey] = authKeys.public.toHex()
            }
        }

        //Mock the service
        affiliateService = Mockito.mock(AffiliateService::class.java)
        osClient = Mockito.mock(OsClient::class.java)
        mailboxService = Mockito.mock(MailboxService::class.java)
        envelopeStateEngine = Mockito.mock(EnvelopeStateEngine::class.java)
        eventService = Mockito.mock(EventService::class.java)
        metricService = Mockito.mock(MetricsService::class.java)
        contractEngine = Mockito.mock(ContractEngine::class.java)
        dimeInputStream = Mockito.mock(DIMEInputStream::class.java)

        envelopeStateEngine = EnvelopeStateEngine()

        signerFactory = Mockito.mock(SignerFactory::class.java)

        envelopeService = EnvelopeService(
            affiliateService = affiliateService,
            osClient = osClient,
            mailboxService = mailboxService,
            envelopeStateEngine = envelopeStateEngine,
            eventService = eventService,
            metricsService = metricService
        )
    }


    @Test
    fun `Verify staged contract that does not exist`(){
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)

        Mockito.`when`(affiliateService.getSigningKeyPair(ecKeys.public)).thenReturn(signingKeys)

        Assert.assertEquals(ContractScope.Envelope.Status.CREATED, testEnvelope.status)

        //Execute
        val envelopeResult = transaction { envelopeService.stage(ecKeys.public, testEnvelope) }

        //Validate
        Assert.assertNotEquals(ContractScope.Envelope.Status.CREATED, envelopeResult.status)
        Assert.assertEquals(ContractScope.Envelope.Status.INBOX, envelopeResult.status)
    }

    @Test
    fun `Verify staged contracts that already exist`() {
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)
        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(false)
            .setContractClassname("HellowWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().plusSeconds(5).toProtoTimestampProv())
            .setInboxTime(OffsetDateTime.now().plusSeconds(10).toProtoTimestampProv())
            .build()

        transaction {
            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = testEnvelope.ref.groupUuid.toUuidProv()
                executionUuid = testEnvelope.executionUuid.toUuidProv()
                publicKey = signingKeys.public.toHex()
                data = envelopeState
                status = ContractScope.Envelope.Status.CREATED
                scopeUuid = scopeRecord.uuid
            }

            //Assumption that EC keys and Signing keys are the same.
            Mockito.`when`(affiliateService.getSigningKeyPair(ecKeys.public)).thenReturn(signingKeys)

            //Execute
            val envelopeResult = envelopeService.stage(ecKeys.public, testEnvelope)

            //Validate
            Assert.assertEquals(envelopeRecord.scope.scopeUuid, envelopeResult.scope.scopeUuid)
            Assert.assertEquals(envelopeRecord.executionUuid, envelopeResult.executionUuid)
            Assert.assertEquals(envelopeRecord.uuid.value, envelopeResult.uuid.value)
        }
    }

    @Test
    fun `Validate read envelope has been read with read timestamp`() {
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)
        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(false)
            .setContractClassname("HellowWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().plusSeconds(5).toProtoTimestampProv())
            .setInboxTime(OffsetDateTime.now().plusSeconds(10).toProtoTimestampProv())
            .build()

        transaction {
            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = testEnvelope.ref.groupUuid.toUuidProv()
                executionUuid = testEnvelope.executionUuid.toUuidProv()
                publicKey = signingKeys.public.toHex()
                data = envelopeState
                status = ContractScope.Envelope.Status.CREATED
                scopeUuid = scopeRecord.uuid
            }
        }

        //Execute
        val envelopeResult = transaction { envelopeService.read(signingKeys.public, testEnvelope.executionUuid.toUuidProv()) }

        //Validate
        Assert.assertNotNull(envelopeResult.readTime)
        Assert.assertNotNull(envelopeResult.data.readTime)
        Assert.assertNotEquals(envelopeResult.readTime, envelopeRecord.readTime)
        Assert.assertNotEquals(envelopeResult.data.readTime, envelopeRecord.data.readTime)
    }

    @Test
    fun `Validate read envelopes that has been mark as errored`(){

        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)

        val errorUuid = UUID.randomUUID()
        val envelopeError = ContractScope.EnvelopeError.newBuilder()
            .setUuid(errorUuid.toProtoUuidProv())
            .setGroupUuid(testEnvelope.ref.groupUuid)
            .setExecutionUuid(testEnvelope.executionUuid)
            .setType(ContractScope.EnvelopeError.Type.CONTRACT_INVOCATION)
            .setMessage("some-error")
            .setReadTime(OffsetDateTime.now().toProtoTimestampProv())
            .setScopeUuid(testEnvelope.scope.uuid)
            .setEnvelope(testEnvelope)
            .auditedProv()
            .build()

        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HellowWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().plusSeconds(5).toProtoTimestampProv())
            .setInboxTime(OffsetDateTime.now().plusSeconds(10).toProtoTimestampProv())
            .addAllErrors(mutableListOf(envelopeError))
            .build()

        transaction {
            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = testEnvelope.ref.groupUuid.toUuidProv()
                executionUuid = testEnvelope.executionUuid.toUuidProv()
                publicKey = signingKeys.public.toHex()
                data = envelopeState
                status = ContractScope.Envelope.Status.CREATED
                scopeUuid = scopeRecord.uuid
            }

            //Execute
            val envelopeResult = envelopeService.read(signingKeys.public, testEnvelope.executionUuid.toUuidProv(), errorUuid)

            //Validate
            Assert.assertEquals(1, envelopeResult.data.errorsCount)
            Assert.assertEquals(ContractScope.EnvelopeError.Type.CONTRACT_INVOCATION, envelopeResult.data.errorsList[0].type)
            Assert.assertNotNull(envelopeError.message, envelopeResult.data.errorsList[0].message)
        }
    }

    @Test
    fun `Validate envelope complete`(){
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)
        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HellowWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().plusSeconds(5).toProtoTimestampProv())
            .setInboxTime(OffsetDateTime.now().plusSeconds(10).toProtoTimestampProv())
            .build()

        transaction {
            EnvelopeTable.insert {
                it[groupUuid] = testEnvelope.ref.groupUuid.toUuidProv()
                it[executionUuid] = testEnvelope.executionUuid.toUuidProv()
                it[publicKey] = signingKeys.public.toHex()
                it[scope] = scopeRecord.uuid
                it[data] = envelopeState
                it[status] = ContractScope.Envelope.Status.INDEX
                it[indexTime] = OffsetDateTime.now()
            }

            //Execute
            envelopeService.complete(signingKeys.public, testEnvelope.executionUuid.toUuidProv())

            //Validate
            val envelopeRecord = EnvelopeTable.selectAll().last()
            Assert.assertEquals(ContractScope.Envelope.Status.COMPLETE, envelopeRecord[EnvelopeTable.status])
            Assert.assertNotNull(envelopeRecord[EnvelopeTable.completeTime])
        }
    }

    @Test(expected = NotFoundException::class)
    fun `Validate NotFoundException is thrown for EnvelopeRecord not in DB`(){
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)

        //Execute
        transaction{ envelopeService.complete(TestUtils.generateKeyPair().public, testEnvelope.executionUuid.toUuidProv()) }

        //Validate - NotFoundException expected.
    }

    @Test
    fun `Validate envelope merge`(){
        val otherKeys = TestUtils.generateKeyPair()
        //Setup
        val contract = TestUtils.generateTestContract(signingKeys, scopeRecord, encryptionKeys = ecKeys)
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, contract = contract, encryptionKeyPair = ecKeys)
        val testEnvelope2 = TestUtils.generateTestEnvelope(otherKeys, scopeRecord, executionUUID = testEnvelope.executionUuid.toUuidProv(), contract = contract)

        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HellowWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().minusSeconds(5).toProtoTimestampProv())
            .build()

        Mockito.`when`(affiliateService.getSigningKeyPair(ecKeys.public)).thenReturn(signingKeys)

        transaction {
            // Mutable Envelope Record
            EnvelopeTable.insert {
                it[groupUuid] = testEnvelope.ref.groupUuid.toUuidProv()
                it[executionUuid] = testEnvelope.executionUuid.toUuidProv()
                it[publicKey] = signingKeys.public.toHex()
                it[scope] = scopeRecord.uuid
                it[data] = envelopeState
                it[status] = ContractScope.Envelope.Status.FRAGMENT
                it[fragmentTime] = OffsetDateTime.now()
            }

            //Execute
            envelopeService.merge(ecKeys.public, testEnvelope2)

            //Validate
            val envelopeRecord = EnvelopeTable.selectAll().last()
            Assert.assertEquals(ContractScope.Envelope.Status.SIGNED, envelopeRecord[EnvelopeTable.status])
            Assert.assertNotNull(envelopeRecord[EnvelopeTable.signedTime])
            Assert.assertEquals(2, envelopeRecord[EnvelopeTable.data].result.signaturesCount)
        }
    }

    @Test
    fun `Validate envelope merge for signature that already exists`(){
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)
        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HellowWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().minusSeconds(5).toProtoTimestampProv())
            .build()

        Mockito.`when`(affiliateService.getSigningKeyPair(ecKeys.public)).thenReturn(signingKeys)

        transaction {
            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = testEnvelope.ref.groupUuid.toUuidProv()
                executionUuid = testEnvelope.executionUuid.toUuidProv()
                publicKey = signingKeys.public.toHex()
                data = envelopeState
                status = ContractScope.Envelope.Status.CHAINCODE
                scopeUuid = scopeRecord.uuid
            }

            //Execute
            val record = envelopeService.merge(ecKeys.public, testEnvelope)

            //Validate
            Assert.assertEquals(envelopeRecord.data, record.data) // Nothing has changed.
        }
    }

    @Test
    fun `Validate envelope index`(){
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)
        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HellowWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().minusSeconds(5).toProtoTimestampProv())
            .build()

        transaction {
            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = testEnvelope.ref.groupUuid.toUuidProv()
                executionUuid = testEnvelope.executionUuid.toUuidProv()
                publicKey = signingKeys.public.toHex()
                data = envelopeState
                status = ContractScope.Envelope.Status.CHAINCODE
                scopeUuid = scopeRecord.uuid
            }

            //Execute
            envelopeService.index(envelopeRecord, testEnvelope.scope, "abc-hash", 100L)

            //Validate
            Assert.assertNotEquals(ContractScope.Envelope.Status.CHAINCODE, envelopeRecord.status)
            Assert.assertEquals(ContractScope.Envelope.Status.INDEX, envelopeRecord.status)
            Assert.assertNotNull(envelopeRecord.indexTime)
        }
    }

    @Test
    fun `Validate indexing does not happen if indexTime is available`(){
        //Setup
        val testIndexTime = OffsetDateTime.now().minusNanos(10)

        transaction {
            scopeRecord = ScopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), ScopeTable)
                publicKey = signingKeys.public.toHex()
                scopeUuid = UUID.randomUUID()
                data = ContractScope.Scope.getDefaultInstance()
                lastExecutionUuid = UUID.randomUUID()

            }

            val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)
            val envelopeState = ContractScope.EnvelopeState.newBuilder()
                .setInput(testEnvelope)
                .setResult(testEnvelope)
                .setIsInvoker(true)
                .setContractClassname("HellowWorldContract")
                .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
                .setChaincodeTime(OffsetDateTime.now().minusSeconds(5).toProtoTimestampProv())
                .setIndexTime(testIndexTime.toProtoTimestampProv())
                .build()

            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = testEnvelope.ref.groupUuid.toUuidProv()
                executionUuid = testEnvelope.executionUuid.toUuidProv()
                publicKey = signingKeys.public.toHex()
                data = envelopeState
                status = ContractScope.Envelope.Status.CHAINCODE
                scopeUuid = scopeRecord.uuid
                indexTime = testIndexTime
            }

            //Execute
            envelopeService.index(envelopeRecord, testEnvelope.scope, "abc-hash", 100L)

            //Validate
            Assert.assertEquals(ContractScope.Envelope.Status.CHAINCODE, envelopeRecord.status)
            Assert.assertNotEquals(ContractScope.Envelope.Status.INDEX, envelopeRecord.status)
            Assert.assertNotNull(envelopeRecord.indexTime)
        }
    }

    @Test
    fun `Validate indexing does not happen if Scope has last events`(){
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, false, encryptionKeyPair = ecKeys)
        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HellowWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().minusSeconds(5).toProtoTimestampProv())
            .build()

        transaction {
            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = testEnvelope.ref.groupUuid.toUuidProv()
                executionUuid = testEnvelope.executionUuid.toUuidProv()
                publicKey = signingKeys.public.toHex()
                data = envelopeState
                status = ContractScope.Envelope.Status.CHAINCODE
                scopeUuid = scopeRecord.uuid
            }

            //Execute
            envelopeService.index(envelopeRecord, testEnvelope.scope, "abc-hash", 100L)

            //Validate
            Assert.assertEquals(ContractScope.Envelope.Status.CHAINCODE, envelopeRecord.status)
            Assert.assertNotEquals(ContractScope.Envelope.Status.INDEX, envelopeRecord.status)
            Assert.assertNull(envelopeRecord.indexTime)
        }
    }

    @Test
    fun `Validate errored envelope is recorded`() {
        //Setup
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)

        val errorUuid = UUID.randomUUID()
        val envelopeError = ContractScope.EnvelopeError.newBuilder()
            .setUuid(errorUuid.toProtoUuidProv())
            .setGroupUuid(testEnvelope.ref.groupUuid)
            .setExecutionUuid(testEnvelope.executionUuid)
            .setType(ContractScope.EnvelopeError.Type.CONTRACT_INVOCATION)
            .setMessage("some-error")
            .setReadTime(OffsetDateTime.now().toProtoTimestampProv())
            .setScopeUuid(testEnvelope.scope.uuid)
            .setEnvelope(testEnvelope)
            .auditedProv()
            .build()

        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HelloWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().plusSeconds(5).toProtoTimestampProv())
            .setInboxTime(OffsetDateTime.now().plusSeconds(10).toProtoTimestampProv())
            .build()

        Mockito.`when`(affiliateService.getSigningPublicKey(ecKeys.public)).thenReturn(signingKeys.public)

        transaction {
            EnvelopeTable.insert {
                it[groupUuid] = testEnvelope.ref.groupUuid.toUuidProv()
                it[executionUuid] = testEnvelope.executionUuid.toUuidProv()
                it[publicKey] = signingKeys.public.toHex()
                it[scope] = scopeRecord.uuid
                it[data] = envelopeState
                it[status] = ContractScope.Envelope.Status.ERROR
                it[errorTime] = OffsetDateTime.now()
            }

            //Execute
            envelopeService.error(ecKeys.public, envelopeError)

            //Validate
            val record = EnvelopeTable.selectAll().last()
            Assert.assertNotNull(record[EnvelopeTable.data].errorsList[0])
            Assert.assertEquals(ContractScope.EnvelopeError.Type.CONTRACT_INVOCATION, record[EnvelopeTable.data].errorsList[0].type)
        }
    }

    @Test
    fun `Validate errored envelope is recorded when two affiliates are on same instance and other is already errored`() {
        val testEnvelope = TestUtils.generateTestEnvelope(signingKeys, scopeRecord, encryptionKeyPair = ecKeys)

        val errorUuid = UUID.randomUUID()
        val envelopeError = ContractScope.EnvelopeError.newBuilder()
            .setUuid(errorUuid.toProtoUuidProv())
            .setGroupUuid(testEnvelope.ref.groupUuid)
            .setExecutionUuid(testEnvelope.executionUuid)
            .setType(ContractScope.EnvelopeError.Type.CONTRACT_REJECTED)
            .setMessage("some-error")
            .setReadTime(OffsetDateTime.now().toProtoTimestampProv())
            .setScopeUuid(testEnvelope.scope.uuid)
            .setEnvelope(testEnvelope)
            .auditedProv()
            .build()

        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HelloWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().plusSeconds(5).toProtoTimestampProv())
            .setInboxTime(OffsetDateTime.now().plusSeconds(10).toProtoTimestampProv())
            .build()

        val secondKeyPair = TestUtils.generateKeyPair()

        Mockito.`when`(affiliateService.getSigningPublicKey(ecKeys.public)).thenReturn(signingKeys.public)
        Mockito.`when`(affiliateService.getSigningPublicKey(secondKeyPair.public)).thenReturn(secondKeyPair.public)

        transaction {
            AffiliateTable.insert {
                it[alias] = "alias2"
                it[publicKey] = secondKeyPair.public.toHex()
                it[privateKey] = secondKeyPair.private.toHex()
                it[whitelistData] = null
                it[encryptionPublicKey] = secondKeyPair.public.toHex()
                it[encryptionPrivateKey] = secondKeyPair.private.toHex()
                it[active] = true
                it[indexName] = "scopes"
                it[signingKeyUuid] = UUID.randomUUID()
                it[encryptionKeyUuid] = UUID.randomUUID()
                it[keyType] = DATABASE
                it[authPublicKey] = secondKeyPair.public.toHex()
            }

            val scopeRecord2 = ScopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), ScopeTable)
                publicKey = secondKeyPair.public.toHex()
                scopeUuid = scopeRecord.scopeUuid
                data = scopeRecord.data
                lastExecutionUuid = scopeRecord.lastExecutionUuid
            }

            EnvelopeTable.insert {
                it[groupUuid] = testEnvelope.ref.groupUuid.toUuidProv()
                it[executionUuid] = testEnvelope.executionUuid.toUuidProv()
                it[publicKey] = signingKeys.public.toHex()
                it[scope] = scopeRecord.uuid
                it[data] = envelopeState
                it[status] = ContractScope.Envelope.Status.ERROR
                it[errorTime] = OffsetDateTime.now()
            }

            EnvelopeTable.insert {
                it[groupUuid] = testEnvelope.ref.groupUuid.toUuidProv()
                it[executionUuid] = testEnvelope.executionUuid.toUuidProv()
                it[publicKey] = secondKeyPair.public.toHex()
                it[scope] = scopeRecord2.uuid
                it[data] = envelopeState
                it[status] = ContractScope.Envelope.Status.ERROR
                it[errorTime] = OffsetDateTime.now()
            }

            //Execute
            envelopeService.error(secondKeyPair.public, envelopeError)
            envelopeService.error(ecKeys.public, envelopeError)

            //Validate
            EnvelopeTable.select { EnvelopeTable.executionUuid eq testEnvelope.executionUuid.toUuidProv() }.forEach {
                Assert.assertEquals(1, it[EnvelopeTable.data].errorsList.count())
                Assert.assertNotNull(it[EnvelopeTable.data].errorsList[0])
                Assert.assertEquals(ContractScope.EnvelopeError.Type.CONTRACT_REJECTED, it[EnvelopeTable.data].errorsList[0].type)
            }
        }
    }

    @Test
    fun `Verify envelope error can be handled for affiliate with different signing and encryption keys `() {
        //Setup
        val encryptionKeyPair = TestUtils.generateKeyPair()
        val signingKeyPair = TestUtils.generateKeyPair()
        val authKeyPair = TestUtils.generateKeyPair()
        transaction {
            AffiliateTable.insert {
                it[alias] = "diff-keys"
                it[publicKey] = signingKeyPair.public.toHex()
                it[privateKey] = signingKeyPair.private.toHex()
                it[whitelistData] = null
                it[encryptionPublicKey] = encryptionKeyPair.public.toHex()
                it[encryptionPrivateKey] = encryptionKeyPair.private.toHex()
                it[active] = true
                it[indexName] = "scopes"
                it[signingKeyUuid] = UUID.randomUUID()
                it[encryptionKeyUuid] = UUID.randomUUID()
                it[keyType] = DATABASE
                it[authPublicKey] = authKeyPair.public.toHex()
            }

            scopeRecord = ScopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), ScopeTable)
                publicKey = signingKeyPair.public.toHex()
                scopeUuid = UUID.randomUUID()
                data = ContractScope.Scope.getDefaultInstance()
                lastExecutionUuid = UUID.randomUUID()
            }
        }

        Mockito.`when`(affiliateService.getSigningPublicKey(encryptionKeyPair.public)).thenReturn(signingKeyPair.public)

        val testEnvelope = TestUtils.generateTestEnvelope(signingKeyPair, scopeRecord, encryptionKeyPair = encryptionKeyPair)

        val errorUuid = UUID.randomUUID()
        val envelopeError = ContractScope.EnvelopeError.newBuilder()
            .setUuid(errorUuid.toProtoUuidProv())
            .setGroupUuid(testEnvelope.ref.groupUuid)
            .setExecutionUuid(testEnvelope.executionUuid)
            .setType(ContractScope.EnvelopeError.Type.CONTRACT_REJECTED)
            .setMessage("some-error")
            .setReadTime(OffsetDateTime.now().toProtoTimestampProv())
            .setScopeUuid(testEnvelope.scope.uuid)
            .setEnvelope(testEnvelope)
            .auditedProv()
            .build()

        val envelopeState = ContractScope.EnvelopeState.newBuilder()
            .setInput(testEnvelope)
            .setResult(testEnvelope)
            .setIsInvoker(true)
            .setContractClassname("HelloWorldContract")
            .setExecutedTime(OffsetDateTime.now().toProtoTimestampProv())
            .setChaincodeTime(OffsetDateTime.now().plusSeconds(5).toProtoTimestampProv())
            .setInboxTime(OffsetDateTime.now().plusSeconds(10).toProtoTimestampProv())
            .build()

        transaction {
            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = testEnvelope.ref.groupUuid.toUuidProv()
                executionUuid = testEnvelope.executionUuid.toUuidProv()
                publicKey = signingKeyPair.public.toHex() // different key than what is secified in envelopeService.error call
                data = envelopeState
                status = ContractScope.Envelope.Status.CREATED
                scopeUuid = scopeRecord.uuid
            }

            //Execute
            envelopeService.error(encryptionKeyPair.public, envelopeError)

            //Validate
            val record = EnvelopeTable.selectAll().last()
            Assert.assertNotNull(record[EnvelopeTable.data].errorsList[0])
            Assert.assertEquals(ContractScope.EnvelopeError.Type.CONTRACT_REJECTED, record[EnvelopeTable.data].errorsList[0].type)
        }
    }
}
