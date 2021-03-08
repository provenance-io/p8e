package io.provenance.pbc.esc.events.account

import io.provenance.pbc.esc.events.ext.*
import io.provenance.pbc.ess.proto.EventProtos.EventBatch

// Event types required for consuming account attribute events from the event stream service.
val accountAttributeEventTypes = listOf("account_attribute_added", "account_attribute_deleted")

// Concrete type for an account attribute event
data class AccountAttribute(
    val eventType: String,
    val name: String, // Only set when 'account_attribute_deleted'
    val address: String,
    val height: Long,
    val attribute: ByteArray? = null,  // Either binary json or protobuf when 'account_attribute_added'
    val attributeType: String = "json" // TODO: Figure out how to set this after chain migration to protobuf
)

// For readability
typealias AccountAttributes = List<AccountAttribute>
typealias AccountAttributesHandler = (AccountAttributes) -> Unit

// A mapper function for converting an event batch to a list of name events.
val accountAttributeMapper: (EventBatch) -> AccountAttributes = { batch: EventBatch ->
    batch.eventsList
        .filter { accountAttributeEventTypes.contains(it.eventType) }
        .map { event ->
            AccountAttribute(
                eventType = event.eventType,
                name = event.findAttribute("attribute_name"),
                address = event.findAttribute("account_address"),
                height = batch.height,
                attribute = event.findAttributeBytesOrNull("attribute")
            )
        }
}

// Converts a name event handler to an event batch handler for use with event stream client.
fun (AccountAttributesHandler).toBatchHandler(): (EventBatch) -> Unit = {
    this.invoke(accountAttributeMapper(it))
}