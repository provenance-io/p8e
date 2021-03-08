package io.p8e.util

import com.google.protobuf.Timestamp
import com.google.protobuf.TimestampOrBuilder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

// -------------------------------------------------------------------
// -------------------------- Protobuf Timestamp ---------------------
/**
 * Get Timestamp as OffsetDateTime (system time zone)
 */
fun TimestampOrBuilder.toOffsetDateTimeProv(): OffsetDateTime = toOffsetDateTimeProv(ZoneId.systemDefault())

/**
 * Get Timestamp as OffsetDateTime
 */
fun TimestampOrBuilder.toOffsetDateTimeProv(zoneId: ZoneId): OffsetDateTime = OffsetDateTime.ofInstant(toInstantProv(), zoneId)

/**
 * Get Timestamp as Instant
 */
fun TimestampOrBuilder.toInstantProv(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

/**
 * Quick convert OffsetDateTime to Timestamp
 */
fun OffsetDateTime.toProtoTimestampProv(): Timestamp = Timestamp.newBuilder().setValueProv(this).build()

/**
 * Store OffsetDateTime as Timestamp (UTC)
 */
fun Timestamp.Builder.setValueProv(odt: OffsetDateTime): Timestamp.Builder = setValueProv(odt.toInstant())

/**
 * Store Instant as Timestamp (UTC)
 */
fun Timestamp.Builder.setValueProv(instant: Instant): Timestamp.Builder {
    this.nanos = instant.nano
    this.seconds = instant.epochSecond
    return this
}

/**
 * Right Meow
 */
fun TimestampOrBuilder.nowProv(): Timestamp = Instant.now().let { time -> Timestamp.newBuilder().setSeconds(time.epochSecond).setNanos(time.nano).build() }
