package io.provenance.p8e.webservice.domain

import com.fasterxml.jackson.annotation.JsonCreator
import io.p8e.util.*
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.p8e.shared.domain.AffiliateRecord
import io.provenance.p8e.shared.domain.AffiliateShareRecord
import io.provenance.p8e.webservice.controller.ApiServiceKey
import io.provenance.p8e.webservice.controller.toApi
import java.security.KeyPair
import java.security.PublicKey
import java.time.OffsetDateTime

data class ApiAffiliateKey(
        val alias: String?,
        val signingKey: ApiPublicKey,
        val encryptionKey: ApiPublicKey,
        val authKey: ApiPublicKey,
        val indexName: String,
        val serviceKeys: List<ApiServiceKey>
) {
    val keyUsage = "CONTRACT"
}

fun AffiliateRecord.toApi(authPrivateKey: String? = null): ApiAffiliateKey {
    return ApiAffiliateKey(
        alias,
        ApiPublicKey(publicKey.value),
        ApiPublicKey(encryptionPublicKey),
        ApiPublicKey(authPublicKey, hexPrivateKey = authPrivateKey),
        indexName,
        serviceKeys.map { it.toApi() }
    )
}

data class ApiAffiliateShare(
    val affiliatePublicKey: String,
    val publicKey: String,
    val created: OffsetDateTime
)

fun AffiliateShareRecord.toApi(): ApiAffiliateShare =
    ApiAffiliateShare(
        affiliatePublicKey,
        publicKey,
        created
    )

enum class KeyProviders {
    DATABASE,
    SMART_KEY,
}

data class RegisterAffiliateKey(val signingPrivateKey: String?, val encryptionPrivateKey: String?, val keyProvider: KeyProviders, val indexName: String, val alias: String?) {
    val hasSigningKey = signingPrivateKey?.isNotBlank() == true
    val hasEncryptionKey = encryptionPrivateKey?.isNotBlank() == true
}

data class UpdateAffiliateKey(val alias: String?)
data class AddShare(val publicKey: String)
data class AttachServiceKeys(val serviceKeys: List<String>) {
    var servicePublicKeys: List<PublicKey> = serviceKeys.map { it.toJavaPublicKey() }
}
