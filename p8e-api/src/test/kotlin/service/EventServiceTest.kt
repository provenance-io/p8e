package service

import com.nhaarman.mockitokotlin2.*
import helper.TestUtils
import io.p8e.proto.ContractScope
import io.p8e.proto.Envelope
import io.p8e.proto.Events
import io.provenance.engine.service.EventService
import io.provenance.engine.service.NotificationHandler
import io.p8e.proto.Events.P8eEvent.Event
import io.p8e.util.toByteString
import io.p8e.util.toHex
import io.p8e.util.toProtoTimestampProv
import io.p8e.util.toProtoUuidProv
import io.provenance.engine.domain.*
import io.provenance.engine.grpc.v1.toEvent
import io.provenance.engine.service.EventHandler
import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.shared.domain.EnvelopeTable
import io.provenance.p8e.shared.domain.ScopeRecord
import io.provenance.p8e.shared.domain.ScopeTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.*

class EventServiceTest {

    lateinit var scopeRecord: ScopeRecord

    lateinit var envelopeRecord: EnvelopeRecord

    lateinit var affiliateConnectionRecord: AffiliateConnectionRecord

    lateinit var notificationHandler: NotificationHandler

    lateinit var eventService: EventService

    val createdTime = OffsetDateTime.now()

    @Before
    fun setup(){
        TestUtils.DatabaseConnect()

        val key = TestUtils.generateKeyPair()

        val testData = ContractScope.EnvelopeState.newBuilder()
            .setContractClassname("HelloWorldContract")
            .build()

        //setup row in event table
        transaction {
            SchemaUtils.create(EventTable)
            SchemaUtils.create(EnvelopeTable)
            SchemaUtils.create(ScopeTable)
            SchemaUtils.create(AffiliateConnectionTable)

            affiliateConnectionRecord = AffiliateConnectionRecord.new {
                publicKey = key.public.toHex()
                classname = "HelloWorldContract"
                connectionStatus = ConnectionStatus.CONNECTED
                lastHeartbeat = OffsetDateTime.now()
            }

            scopeRecord = ScopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), ScopeTable)
                publicKey = key.public.toHex()
                scopeUuid = UUID.randomUUID()
                data = ContractScope.Scope.getDefaultInstance()
                lastExecutionUuid = UUID.randomUUID()
            }

            envelopeRecord = EnvelopeRecord.new {
                uuid = EntityID(UUID.randomUUID(), EnvelopeTable)
                groupUuid = UUID.randomUUID()
                executionUuid = UUID.randomUUID()
                publicKey = key.public.toHex()
                data = testData
                status = ContractScope.Envelope.Status.CREATED
                scopeUuid = scopeRecord.uuid
            }
        }

        eventService = EventService()
        notificationHandler = NotificationHandler(Event.ENVELOPE_REQUEST)

    }

    @Test
    fun `Test in-progress events are updated to completed`() {
        transaction {
            val event = Events.P8eEvent.newBuilder()
                .setEvent(Event.ENVELOPE_REQUEST)
                .setMessage("some-test-message".toByteString())
                .build()

            EventRecord.insert(event, envelopeRecord.uuid.value)

            //Execute
            eventService.completeInProgressEvent(envelopeRecord.uuid.value, Event.ENVELOPE_REQUEST)

            //Validate
            val lastUpdatedRow = EventTable.selectAll().last()
            Assert.assertEquals(EventStatus.COMPLETE, lastUpdatedRow[EventTable.status])
        }
    }

    @Test
    fun `Verify register callback invocations`(){
        //Mock the typealias - doesn't do anything.
        val mockObj = object: EventHandler {
            override fun invoke(event: Events.P8eEvent): EventStatus? {
                return EventStatus.CREATED
            }
        }

        //Partial mock the NotificationHandler companion object
        val callbacks = NotificationHandler.callbacks.computeIfAbsent(Event.ENVELOPE_REQUEST){ Mockito.spy(mutableListOf()) }

        // Void method, just want to verify that is able to run everything in the code block.
        eventService.registerCallback(Event.ENVELOPE_REQUEST, mockObj)

        // Verify nothing was add with an empty object.
        Mockito.verify(callbacks, times(1)).add(mockObj)
    }

    @Test
    fun `Verify submitEvent Event Record`(){
        //Setup
        val event = Events.P8eEvent.newBuilder()
            .setEvent(Event.ENVELOPE_CHAINCODE)
            .setMessage("some-test-message".toByteString())
            .build()

        //Execute
        val testEventRecord = transaction { eventService.submitEvent(event, envelopeRecord.uuid.value, EventStatus.CREATED, createdTime) }

        //Validate
        Assert.assertEquals(event.event, testEventRecord.event)
        Assert.assertEquals(envelopeRecord.uuid.value, testEventRecord.envelopeUuid)
        Assert.assertEquals(EventStatus.CREATED, testEventRecord.status)
        Assert.assertEquals(createdTime, testEventRecord.created)
    }

    @Test
    fun `Validate able to submit event of type error`() {
        //Setup
        val envError = transaction {
            Envelope.EnvelopeUuidWithError.newBuilder()
                .setError(
                    ContractScope.EnvelopeError.newBuilder()
                        .setUuid(envelopeRecord.uuid.value.toProtoUuidProv())
                        .setGroupUuid(envelopeRecord.groupUuid.toProtoUuidProv())
                        .setExecutionUuid(envelopeRecord.executionUuid.toProtoUuidProv())
                        .setType(ContractScope.EnvelopeError.Type.CONTRACT_INVOCATION)
                        .setMessage("error")
                        .setReadTime(OffsetDateTime.now().toProtoTimestampProv())
                        .setScopeUuid(envelopeRecord.scope.scopeUuid.toProtoUuidProv())
                        .setEnvelope(ContractScope.Envelope.getDefaultInstance())
                        .build()
                )
                .setEnvelopeUuid(envelopeRecord.uuid.value.toProtoUuidProv())
                .build()
        }

        //Execute
        val testErrorEventRecord = transaction { eventService.submitEvent(envError.toEvent(Event.ENVELOPE_ERROR), envelopeRecord.uuid.value) }

        //Validate
        Assert.assertEquals(Event.ENVELOPE_ERROR, testErrorEventRecord.event)
        Assert.assertEquals(EventStatus.CREATED, testErrorEventRecord.status)
        Assert.assertEquals(envelopeRecord.uuid.value, testErrorEventRecord.envelopeUuid)
    }

    @Test
    fun `Verify event submission skipped for invalid transition`() {
        val event = Events.P8eEvent.newBuilder()
            .setEvent(Event.ENVELOPE_RESPONSE)
            .setMessage("some-test-message".toByteString())
            .build()

        transaction { EventRecord.insert(event, envelopeRecord.uuid.value) }

        val event2 = Events.P8eEvent.newBuilder()
            .setEvent(Event.SCOPE_INDEX)
            .setMessage("some-other-test-message".toByteString())
            .build()
        val updatedRecord = transaction { eventService.submitEvent(event2, envelopeRecord.uuid.value, EventStatus.CREATED, createdTime) }

        // event should remain unchanged, since you can't go from ENVELOPE_RESPONSE -> SCOPE_INDEX
        Assert.assertEquals(Event.ENVELOPE_RESPONSE, updatedRecord.event)
        Assert.assertEquals("some-test-message".toByteString(), updatedRecord.payload.message)
    }

    @Test
    fun `Verify event submission works for a valid transition`() {
        val event = Events.P8eEvent.newBuilder()
            .setEvent(Event.SCOPE_INDEX)
            .setMessage("some-test-message".toByteString())
            .build()

        transaction { EventRecord.insert(event, envelopeRecord.uuid.value) }

        val event2 = Events.P8eEvent.newBuilder()
            .setEvent(Event.ENVELOPE_RESPONSE)
            .setMessage("some-other-test-message".toByteString())
            .build()
        val updatedRecord = transaction { eventService.submitEvent(event2, envelopeRecord.uuid.value, EventStatus.CREATED, createdTime) }

        // event should be updated, since you can go from SCOPE_INDEX -> ENVELOPE_RESPONSE
        Assert.assertEquals(Event.ENVELOPE_RESPONSE, updatedRecord.event)
        Assert.assertEquals("some-other-test-message".toByteString(), updatedRecord.payload.message)
    }

    @Test
    fun `Verify ENVELOPE_CHAINCODE to ENVELOPE_CHAINCODE is a valid transition`() {
        // CHAINCODE -> CHAINCODE transition for TransactionStatusService.retryDead case
        val event = Events.P8eEvent.newBuilder()
            .setEvent(Event.ENVELOPE_CHAINCODE)
            .setMessage("some-test-message".toByteString())
            .build()

        transaction { EventRecord.insert(event, envelopeRecord.uuid.value) }

        val event2 = Events.P8eEvent.newBuilder()
            .setEvent(Event.ENVELOPE_CHAINCODE)
            .setMessage("some-other-test-message".toByteString())
            .build()
        val updatedRecord = transaction { eventService.submitEvent(event2, envelopeRecord.uuid.value, EventStatus.CREATED, createdTime) }

        // event should be updated, since you can go from ENVELOPE_CHAINCODE -> ENVELOPE_CHAINCODE
        Assert.assertEquals("some-other-test-message".toByteString(), updatedRecord.payload.message)
    }
}
