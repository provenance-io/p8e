package io.provenance.p8e.encryption.util

import com.fortanix.sdkms.v1.api.SecurityObjectsApi

/**
 * A wrapper class for all external key custody apis that are needed.
 *
 */
class ExternalKeyCustodyApi(
    private val securityObjectsApi: SecurityObjectsApi
) {
    /**
     * Hold a reference of the external key custody apis for usage without resetting the connection of
     * of previous API connection that was setup during AppConfig time.
     *
     * ~~Add any new needed key custody APIs below.~~
     *
     */

    fun getSmartKeySecurityObj(): SecurityObjectsApi {
        return securityObjectsApi
    }

}
