package io.provenance.engine.stream.domain

import com.tinder.scarlet.WebSocket
import com.tinder.scarlet.ws.Receive
import com.tinder.scarlet.ws.Send
import io.p8e.util.base64String
import io.reactivex.Flowable

interface EventStreamService {
    @Receive
    fun observeWebSocketEvent(): Flowable<WebSocket.Event>
    @Send
    fun subscribe(subscribe: Subscribe)
    @Receive
    fun streamEvents(): Flowable<RPCResponse<Result>>
    @Send
    fun unsubscribe(unsubscribe: Unsubscribe = Unsubscribe())
}

data class Result(
    val query: String?,
    val data: ResultData
)

data class ResultData(
    val type: String,
    val value: ResultValue
)

data class ResultValue(
    val block: Block,
)

data class Block(
    val header: BlockHeader,
    val data: BlockData
)

data class BlockHeader(
    val height: Long
)

data class BlockData(
    val txs: List<ByteArray>
)

data class Event(
    val type: String,
    val attributes: List<Attribute>
)

data class EventBatch(
    val height: Long,
    val events: List<StreamEvent>
)

data class StreamEvent(
    val height: Long,
    val eventType: String,
    val attributes: List<Attribute>,
    val resultIndex: Int,
    val txHash: String
)

class Attribute(
    key: ByteArray,
    value: ByteArray
) {
    val key = String(key)
    val value = String(value)
}

data class Subscribe(
    val query: String
) : RPCRequest("subscribe", SubscribeParams(query))

class Unsubscribe() : RPCRequest("unsubscribe")

open class RPCRequest(val method: String, val params: Any? = null) {
    val jsonrpc = "2.0"
    val id = "0"
}

data class RPCResponse<T>(
    val jsonrpc: String,
    val id: String,
    val result: T
)

data class SubscribeParams(
    val query: String
)
