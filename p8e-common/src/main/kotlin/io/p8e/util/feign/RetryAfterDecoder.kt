package io.p8e.util.feign

import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RetryAfterDecoder(
    private val rfc822Format: DateFormat = RFC822_FORMAT
) {

    private fun currentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    /**
     * returns a date that corresponds to the first time a request can be retried.
     *
     * @param retryAfter String in
     * [Retry-After format](https://tools.ietf.org/html/rfc2616#section-14.37)
     */
    fun apply(retryAfter: String?): Date? {
        if (retryAfter == null) {
            return null
        }
        if (retryAfter.matches("^[0-9]+$".toRegex())) {
            val deltaMillis = TimeUnit.SECONDS.toMillis(java.lang.Long.parseLong(retryAfter))
            return Date(currentTimeMillis() + deltaMillis)
        }
        synchronized(rfc822Format) {
            try {
                return rfc822Format.parse(retryAfter)
            } catch (ignored: ParseException) {
                return null
            }

        }
    }

    companion object {
        internal val RFC822_FORMAT: DateFormat = SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            Locale.US
        )
    }
}