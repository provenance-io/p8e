package io.provenance.p8e.webservice.service

import com.fortanix.sdkms.v1.api.SecurityObjectsApi
import com.fortanix.sdkms.v1.model.*
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toUuidProv
import io.provenance.p8e.encryption.model.ExternalKeyRef
import io.provenance.p8e.shared.extension.logger
import org.springframework.stereotype.Service
import java.security.KeyPair
import java.util.*

enum class KeyUsageType(val options: List<KeyOperations>) {
    SIGNING(listOf(KeyOperations.SIGN, KeyOperations.VERIFY, KeyOperations.APPMANAGEABLE)),
    ENCRYPTION(listOf(KeyOperations.AGREEKEY, KeyOperations.APPMANAGEABLE)),
}

@Service
class KeyManagementService(private val securityObjectsApi: SecurityObjectsApi) {
    fun importKey(keyPair: KeyPair, name: String?, keyUsageType: KeyUsageType): ExternalKeyRef =
        importOrGenerateKey(SobjectRequest()
            .objType(ObjectType.EC)
            .value(keyPair.private.encoded)
            .keyOps(keyUsageType.options)
            .name(name)
        )

    fun generateKey(name: String?, keyUsageType: KeyUsageType): ExternalKeyRef =
        importOrGenerateKey(SobjectRequest()
            .objType(ObjectType.EC)
            .ellipticCurve(EllipticCurve.SECP256K1)
            .keyOps(keyUsageType.options)
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
