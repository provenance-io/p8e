package io.provenance.p8e.webservice.domain

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
        val indexName: String,
        val serviceKeys: List<ApiServiceKey>
) {
    val keyUsage = "CONTRACT"
}

fun AffiliateRecord.toApi(includePrivateKey: Boolean = false): ApiAffiliateKey {
    return ApiAffiliateKey(
        alias,
        ApiPublicKey(publicKey.value, hexPrivateKey = privateKey.takeIf { includePrivateKey }),
        ApiPublicKey(encryptionPublicKey, hexPrivateKey = encryptionPrivateKey.takeIf { includePrivateKey }),
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

data class RegisterAffiliateKey(val signingPrivateKey: String?, val encryptionPrivateKey: String?, val authKeyPair: String?, val useSigningKeyForEncryption: Boolean, val indexName: String, val alias: String?) {
    val signingKeyPair: KeyPair = signingPrivateKey.toOrGenerateKeyPair()
    val encryptionKeyPair: KeyPair = signingKeyPair.takeIf { useSigningKeyForEncryption }.or { encryptionPrivateKey.toOrGenerateKeyPair() }
    val authPublicKey: PublicKey = authKeyPair.toOrGenerateKeyPair().public

    private fun String?.toOrGenerateKeyPair() = this?.takeIf { it.isNotBlank() }?.toJavaPrivateKey()?.let {
        KeyPair(it.computePublicKey(), it)
    } ?: ProvenanceKeyGenerator.generateKeyPair()
}

data class UpdateAffiliateKey(val alias: String?)
data class AddShare(val publicKey: String)
data class AttachServiceKeys(val serviceKeys: List<String>) {
    var servicePublicKeys: List<PublicKey> = serviceKeys.map { it.toJavaPublicKey() }
}
