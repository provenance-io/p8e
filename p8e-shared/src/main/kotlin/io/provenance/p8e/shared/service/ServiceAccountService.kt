package io.provenance.p8e.shared.service

import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.shared.domain.ServiceAccountRecord
import io.provenance.p8e.shared.domain.ServiceIdentityRecord
import org.springframework.stereotype.Component
import java.security.KeyPair
import java.security.PublicKey
import java.util.*

@Component
class ServiceAccountService(private val keystoneService: KeystoneService) {
    /**
     * Get all service accounts
     *
     * @return the [ServiceAccountRecord] list
     */
    fun getAll(): List<ServiceAccountRecord> = ServiceAccountRecord.all().toList()

    /**
     * Add a service account key
     *
     * @param privateKey the private key to insert
     * @param status the status of the service account key
     * @param alias a human-readable alias for this key
     *
     * @return the [ServiceAccountRecord] added
     */
    fun save(keyPair: KeyPair, status: String, alias: String?) = ServiceAccountRecord.insert(keyPair, status, alias)

    /**
     * Update an existing service account key
     *
     * @param status the status of the service account key
     * @param alias a human-readable alias for this key
     *
     * @return the [ServiceAccountRecord] updated
     */
    fun update(publicKey: PublicKey, status: String? = null, alias: String? = null) = ServiceAccountRecord.findForUpdate(publicKey)
        ?.also {serviceAccount ->
            status?.takeIf { it.isNotBlank() }?.let { serviceAccount.status = status }
            alias?.takeIf { it.isNotBlank() }?.let { serviceAccount.alias = alias }
        }
}
