package io.p8e.spec

import io.p8e.proto.Common.BooleanResult
import io.p8e.proto.Util
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Provide basic functionality for agreement setup.
 */
abstract class P8eContract {
    val uuid = Util.UUID.newBuilder().setValue(UUID.randomUUID().toString()).build()
    val currentTime = AtomicReference<OffsetDateTime?>()

    // By invoking the consideration you are indicating your agreement with the consideration.
    fun impliedConsent() = BooleanResult.newBuilder().setValue(true).build()

    protected fun getCurrentTime(): OffsetDateTime {
        return currentTime.get()
            ?: throw IllegalStateException("Current time wasn't set prior to contract construction.")
    }
}
