package io.provenance.pbc.esc.events.name

import io.provenance.pbc.esc.events.ext.*
import io.provenance.pbc.ess.proto.EventProtos.EventBatch

// Event types required for consuming transfer events from the event stream service.
val namesEventTypes = listOf("name_bound")

// Concrete type for a name event
data class Name(val name: String, val address: String, val height: Long)

// For readability
typealias Names = List<Name>
typealias NamesHandler = (Names) -> Unit

// A mapper function for converting an event batch to a list of name events.
val namesMapper: (EventBatch) -> Names = { batch: EventBatch ->
    batch.eventsList
        .filter { namesEventTypes.contains(it.eventType) }
        .map { event ->
            Name(
                name = event.findAttribute("name"),
                address = event.findAttribute("address"),
                height = batch.height
            )
        }
}

// Converts a name event handler to an event batch handler for use with event stream client.
fun (NamesHandler).toBatchHandler(): (EventBatch) -> Unit = {
    this.invoke(namesMapper(it))
}