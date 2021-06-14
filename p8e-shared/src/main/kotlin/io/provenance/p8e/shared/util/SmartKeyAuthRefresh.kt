package io.provenance.p8e.shared.util

import com.fortanix.sdkms.v1.api.AuthenticationApi
import io.provenance.p8e.shared.extension.logger
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SmartKeyAuthRefresh(
    private val authenticationApi: AuthenticationApi
) {

    private val log = logger()

    companion object {
        const val REFRESH_RATE: Long = 300_000 // refresh at a rate of every 5 minutes.
    }

    /**
     * Refresh current SmartKey auth session every 5 minutes.
     */
    @Scheduled(fixedRate = REFRESH_RATE)
    fun refreshSmartKeyAuthToken() {
        if(authenticationApi.apiClient != null) {
            log.debug("refreshing SmartKey auth session")
            authenticationApi.refresh()
        }
    }
}
