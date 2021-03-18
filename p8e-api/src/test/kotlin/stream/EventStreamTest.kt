package stream

import com.nhaarman.mockitokotlin2.*
import com.tinder.scarlet.Lifecycle
import com.tinder.scarlet.ShutdownReason
import com.tinder.scarlet.WebSocket
import com.tinder.scarlet.lifecycle.LifecycleRegistry
import io.p8e.util.base64decode
import io.p8e.util.base64encode
import io.provenance.engine.domain.*
import io.provenance.engine.service.TransactionQueryService
import io.provenance.engine.stream.EventStreamFactory
import io.provenance.engine.stream.domain.*
import io.reactivex.Flowable
import io.reactivex.rxkotlin.toFlowable
import org.junit.Before
import org.junit.Test

class EventStreamTest {
    val query = "tm.event='NewBlock'"

    lateinit var observer: EventStreamResponseObserver<EventBatch>

    lateinit var transactionQueryService: TransactionQueryService

    lateinit var lifecycle: LifecycleRegistry

    lateinit var eventStreamService: EventStreamService

    @Before
    fun setUp() {
        observer = mock()

        transactionQueryService = mock()

        lifecycle = mock()

        eventStreamService = mock()
        whenever(eventStreamService.observeWebSocketEvent()).thenReturn(listOf(mock<WebSocket.Event.OnConnectionOpened<WebSocket>>()).toFlowable())
    }

    fun buildEventStream(eventTypes: List<String> = listOf("scope_created", "scope_updated"), startHeight: Long = 100): EventStreamFactory.EventStream {
        return EventStreamFactory.EventStream(eventTypes, startHeight, observer, lifecycle, eventStreamService, transactionQueryService)
    }

    @Test
    fun `History is not streamed if startHeight is less than 0`() {
        setLastBlockHeight(100)
        buildEventStream(startHeight = -1).streamEvents()

        verify(transactionQueryService, times(0)).blocksWithTransactions(any(), any())
    }

    @Test
    fun `History is not streamed if startHeight is greater than the last block height`() {
        setLastBlockHeight(100)
        buildEventStream(startHeight = 101)

        verify(transactionQueryService, times(0)).blocksWithTransactions(any(), any())
    }

    @Test
    fun `Requested events are emitted to the observer`() {
        setLastBlockHeight(99)
        queueEvents(event(100))
        val txResults = listOf(txResult(0, listOf("scope_created")), txResult(0, listOf("scope_updated")))
        setBlockResults(100, txResults)

        buildEventStream().streamEvents()

        verify(observer, times(1)).onNext(any())
    }

    @Test
    fun `Non-requested events are not emitted to the observer`() {
        setLastBlockHeight(99)
        queueEvents(event(100))
        val txResults = listOf(txResult(0, listOf("scope_created")), txResult(0, listOf("scope_updated")))
        setBlockResults(100, txResults)

        buildEventStream(listOf("scope_destroyed")).streamEvents()

        verify(observer, times(0)).onNext(any())
    }

    @Test
    fun `Any Event is emitted if no specific events are requested`() {
        setLastBlockHeight(99)
        queueEvents(event(100), event(101))
        setBlockResults(100, listOf(txResult(0, listOf("scope_created")), txResult(0, listOf("scope_updated"))))
        setBlockResults(101, listOf(txResult(0, listOf("scope_destroyed"))))

        buildEventStream(listOf()).streamEvents()

        verify(observer, times(2)).onNext(any())
    }

    @Test
    fun `Only events with requested attributes are emitted if specified`() {
        setLastBlockHeight(99)
        queueEvents(event(100), event(101))
        setBlockResults(100, listOf(txResult(0, listOf("scope_created"))))
        setBlockResults(101, listOf(txResult(0, listOf("scope_created:my_favorite_attribute"))))

        buildEventStream(listOf("scope_created:my_favorite_attribute")).streamEvents()

        verify(observer, times(1)).onNext(any())
    }

    @Test
    fun `Observer error and complete called when error thrown`() {
        setLastBlockHeight(99)
        val e = Throwable("Fail")
        whenever(eventStreamService.streamEvents()).thenReturn(Flowable.error(e))

        buildEventStream().streamEvents()

        verify(lifecycle, times(1)).onNext(Lifecycle.State.Stopped.WithReason(ShutdownReason.GRACEFUL))
        verify(observer, times(1)).onError(e)
        verify(observer, times(1)).onCompleted()
    }

    fun queueEvents(vararg events: Result) {
        whenever(eventStreamService.streamEvents()).thenReturn(events.map { RPCResponse("2.0", "0", it) }.toFlowable())
    }

    fun event(height: Long) = Result(query, ResultData("tendermint/event/NewBlock", ResultValue(Block(BlockHeader(height), BlockData(null)))))

    fun setLastBlockHeight(height: Long) {
        whenever(transactionQueryService.abciInfo()).thenReturn(ABCIInfo("provenance", height, "last_hash"))
    }

    fun setBlockResults(height: Long, transactions: List<TxResult>) {
        whenever(transactionQueryService.block(height)).thenReturn(BlockResponse(
            BlockID("block_${height}_hash", PartSetHeader(1, "part_${height}_hash")),
            Block(BlockHeader(height), BlockData(transactions.mapIndexed { i, _ -> "txhash" }))
        ))
        whenever(transactionQueryService.blockResults(height)).thenReturn(BlockResults(
            height,
            transactions
        ))
    }

    fun txResult(code: Int, events: List<String>): TxResult {
        return TxResult(code, null, "bunch_of_json", "", 123, 123, events.map {
            val (eventType, attributes) = if (it.contains(':')) it.split(':') else listOf(it, "scope_id,scope,group_id,execution_id,module,tx_hash")
            val attributeTypes = attributes.split(',')

            Event(eventType, attributeTypes.map { attribute -> attribute(attribute, "${attribute}_value") })
        })
    }

    fun attribute(key: String, value: String): Attribute {
        return Attribute(key.base64encode().base64decode(), value.base64encode().base64decode())
    }
}
