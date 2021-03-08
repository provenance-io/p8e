package io.provenance.pbc.esc.events.ext

import io.provenance.pbc.ess.proto.EventProtos.Event

// Determine whether a list of events has a given attribute key
fun (Event).hasAttribute(key: String): Boolean =
    this.attributesList.any { it.key.toStringUtf8() == key }

// Return the value for the given attribute key, or an empty string if not found.
fun (Event).findAttribute(key: String): String =
    this.attributesList.find { it.key.toStringUtf8() == key }?.value?.toStringUtf8() ?: ""

// Return the value for the given attribute key or null if not found.
fun (Event).findAttributeBytesOrNull(key: String): ByteArray? =
    this.attributesList.find { it.key.toStringUtf8() == key }?.value?.toByteArray()

// Return the value for the given attribute key, or an empty string if not found.
fun (List<Event>).findAttribute(key: String): String =
    this.flatMap { it.attributesList }.find { it.key.toStringUtf8() == key }?.value?.toStringUtf8() ?: ""
