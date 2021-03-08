package io.p8e.grpc.client

import io.grpc.Status
import io.grpc.StatusRuntimeException

object GrpcRetry {
    private val retryableErrors = listOf(Status.UNAVAILABLE, Status.INTERNAL)

    fun <T> unavailableBackoff(attempts: Int = 4, base: Int = 250, multiplier: Double = 2.0, f: () -> T): T {
        for (i in 1..attempts-1) {
            try {
                return f()
            } catch(e: StatusRuntimeException) {
                if (!retryableErrors.contains(e.status))
                    throw e
            }

            // Wait to retry
            val wait = (base * Math.pow(multiplier, i.toDouble())).toLong()
            Thread.sleep(wait)
        }

        // Last attempt should just return whatever to the client.
        return f()
    }
}