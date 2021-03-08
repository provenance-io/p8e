package io.p8e.util

import java.util.*
import io.p8e.proto.Util


// -------------------------------------------------------------------
// -------------------------- Util.UUID ------------------------------
/**
 * Build random UUID
 */
fun randomProtoUuidProv(): Util.UUID = Util.UUID.newBuilder().setValueProv(UUID.randomUUID()).build()

/**
 * Get UUID from string
 */
fun String.toUuidProv(): UUID = UUID.fromString(this)

/**
 * Build Proto UUID to String
 */
fun String.toProtoUuidProv(): Util.UUID = UUID.fromString(this).toProtoUuidProv()

/**
 * Build UUID from java.util.UUID
 */
fun UUID.toProtoUuidProv(): Util.UUID = Util.UUID.newBuilder().setValue(this.toString()).build()

/**
 * Store UUID as string
 */
fun Util.UUID.Builder.setValueProv(uuid: UUID): Util.UUID.Builder = setValue(uuid.toString())

/**
 * Get UUID string as UUID
 */
fun Util.UUIDOrBuilder.toUuidProv(): UUID = UUID.fromString(value)

/**
 * Returns a UUID or null
 */
fun Util.UUID.toUuidOrNullProv(): UUID? = if (this.value.isNotEmpty()) this.toUuidProv() else null
