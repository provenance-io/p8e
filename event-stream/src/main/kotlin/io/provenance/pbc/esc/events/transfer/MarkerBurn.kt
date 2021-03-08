package io.provenance.pbc.esc.events.transfer

import io.provenance.pbc.esc.events.ext.*
import io.provenance.pbc.esc.EventStreamClient
import io.provenance.pbc.ess.proto.EventProtos.Event
import io.provenance.pbc.ess.proto.EventProtos.EventBatch

// Event types required for consuming transfer events from the event stream service.
val burnEventTypes = listOf("marker_account_burned_coins")

// Concrete type for a transfer event
//key:marker_account_burned_coins, value:[Attribute(key=denom, value=foocoin6),
//Attribute(key=amount, value=200),
//Attribute(key=administrator, value=tp1rey9tahtvcgjmhfzqltvn4ulf4sdk3aejqadqm),
//Attribute(key=module, value=marker)]
data class Burn(val burnedBy: String, val denom: String, val amount: String, val height: Long, val txHash: String)

// Readability types
typealias Burns = List<Burn>
//sick burn
typealias BurnsHandler = (Burns) -> Unit

// A mapper function for converting an event batch to a list of transfer events.
val sickBurnMapper: (EventBatch) -> Burns = { batch: EventBatch -> batch.burns() }

// Convert an event batch into a list of transfer events.
fun (EventBatch).burns(): Burns =
    this.eventsList
        .filter { it.eventType == "marker_account_burned_coins" }
        .groupBy { it.resultIndex }
        .flatMap { it.value } //not sure if we need the flat map..but why not
        .map { events ->
            makeBurn(this.height, listOf(events))
        }

// Find the tx hash for the burn event
private fun getTxHash(events: List<Event>): String =
    events.find { it.eventType == "marker_account_burned_coins" }?.txHash ?: ""

// Given a chunk of ess events, create a transfer data type.
fun makeBurn(height: Long, events: List<Event>): Burn =
        Burn(
                burnedBy = events.findAttribute("administrator"),
                denom = events.findAttribute("denom"),
                amount = events.findAttribute("amount"),
                height = height,
                txHash = getTxHash(events)
        )

// Converts a transfer handler to an event batch handler for use with event stream client.
fun (BurnsHandler).toBurnHandler(): (EventBatch) -> Unit = {
    this.invoke(it.burns())
}

//// Example: Consume and print transfer events as they're emitted from a local event stream service.
//fun main() {
//
//    val handler = { burns: Burns ->
//       burns.forEach(::println)
//    }
//
//    val errorHandler = { t: Throwable ->
//        t.printStackTrace()
//    }
//
//    val client = EventStreamClient(
//        "localhost", 9090, burnEventTypes, 1, "", handler.toBurnHandler(), errorHandler)
//
//    Runtime.getRuntime().addShutdownHook(Thread {
//        client.stop()
//    })
//
//    client.run()
//}
