package io.p8e.crypto

import com.fortanix.sdkms.v1.api.AuthenticationApi
import com.google.common.util.concurrent.ThreadFactoryBuilder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MINUTES
import kotlin.concurrent.thread

class SmartKeyAuthRefresh(
    private val authenticationApi: AuthenticationApi
) {

    /**
     * Refresh the Auth Token every 5 Minutes
     */
    fun refreshSmartKeyAuthToken() {
        val executor = Executors.newScheduledThreadPool(1, ThreadFactoryBuilder().setDaemon(true).setNameFormat("smartkey-token-refresh-%d").build())
            .apply {
                Runtime.getRuntime().addShutdownHook(
                    thread(start = false) {
                        shutdown()
                    }
                )
            }

        // Token expires at 10 minutes, we will refresh every 5 minutes.
        executor.scheduleAtFixedRate(
            { authenticationApi.refresh() },
            1,
            5,
            MINUTES
        )
    }
}
