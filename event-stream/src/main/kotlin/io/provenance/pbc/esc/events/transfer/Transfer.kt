package io.provenance.pbc.esc.events.transfer

import io.provenance.pbc.esc.events.ext.*
import io.provenance.pbc.ess.proto.EventProtos.Event
import io.provenance.pbc.ess.proto.EventProtos.EventBatch

// Event types required for consuming transfer events from the event stream service.
val transferEventTypes = listOf("transfer", "message")

// Concrete type for a transfer event
data class Transfer(
        val sender: String,
        val recipient: String,
        val amount: String,
        val height: Long,
        val txHash: String
)

// Readability types
typealias Transfers = List<Transfer>
typealias TransfersHandler = (Transfers) -> Unit

// A mapper function for converting an event batch to a list of transfer events.
val transferMapper: (EventBatch) -> Transfers = { batch: EventBatch -> batch.transfers() }

// Convert an event batch into a list of transfer events.
fun (EventBatch).transfers(): Transfers =
        this.eventsList
                .filter { it.eventType == "transfer" || it.hasAttribute("sender") }
                .groupBy { it.resultIndex }
                .flatMap { it.value.chunked(2) }
                .map { events ->
                    makeTransfer(this.height, events)
                }

// Find the tx hash for the transfer event
private fun getTxHash(events: List<Event>): String =
    events.find { it.eventType == "transfer" }?.txHash ?: ""

// Given a chunk of ess events, create a transfer data type.
fun makeTransfer(height: Long, events: List<Event>): Transfer =
        Transfer(
                sender = events.findAttribute("sender"),
                recipient = events.findAttribute("recipient"),
                amount = events.findAttribute("amount"),
                height = height,
                txHash = getTxHash(events)
        )

// Converts a transfer handler to an event batch handler for use with event stream client.
fun (TransfersHandler).toBatchHandler(): (EventBatch) -> Unit = {
    this.invoke(it.transfers())
}
