package io.provenance.p8e.webservice.service

import com.fortanix.sdkms.v1.api.SecurityObjectsApi
import com.fortanix.sdkms.v1.model.EllipticCurve
import com.fortanix.sdkms.v1.model.ObjectType
import com.fortanix.sdkms.v1.model.SobjectRequest
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.domain.ExternalKeyRef
import java.security.KeyPair
import java.util.*

class KeyManagementService(private val securityObjectsApi: SecurityObjectsApi) {
    fun importKey(keyPair: KeyPair, name: String?): ExternalKeyRef =
        importOrGenerateKey(SobjectRequest()
            .objType(ObjectType.EC)
            .value(keyPair.private.encoded)
            .name(name)
        )

    fun generateKey(name: String?): ExternalKeyRef =
        importOrGenerateKey(SobjectRequest()
            .objType(ObjectType.EC)
            .ellipticCurve(EllipticCurve.SECP256K1)
            .name(name)
        )

    fun updateName(uuid: UUID, name: String?) = securityObjectsApi
        .updateSecurityObject(
            uuid.toString(),
            SobjectRequest().name(name)
        )

    fun deleteKey(uuid: UUID) = securityObjectsApi.deleteSecurityObject(uuid.toString())

    private fun importOrGenerateKey(request: SobjectRequest): ExternalKeyRef = securityObjectsApi
        .generateSecurityObject(request).run {
            ExternalKeyRef(kid.toUuidProv(), toJavaPublicKey())
        }
}
