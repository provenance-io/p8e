package io.provenance.engine.extension

import java.time.Duration
import java.time.temporal.ChronoUnit

fun Int.nanos(): Duration = Duration.of(toLong(), ChronoUnit.NANOS)
fun Int.millis(): Duration = Duration.of(toLong(), ChronoUnit.MILLIS)
fun Int.seconds(): Duration = Duration.of(toLong(), ChronoUnit.SECONDS)
fun Int.minutes(): Duration = Duration.of(toLong(), ChronoUnit.MINUTES)
fun Int.hours(): Duration = Duration.of(toLong(), ChronoUnit.HOURS)
fun Int.days(): Duration = Duration.of(toLong(), ChronoUnit.DAYS)