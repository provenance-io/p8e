package io.provenance.p8e.encryption.model

import com.fasterxml.jackson.databind.ObjectMapper
import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt
import io.provenance.p8e.encryption.dime.ProvenanceDIME
import io.provenance.proto.encryption.EncryptionProtos
import io.provenance.proto.encryption.EncryptionProtos.Audience
import java.io.InputStream
import java.util.*
import javax.crypto.spec.SecretKeySpec

open class DIMEProcessingModel(open val dime: EncryptionProtos.DIME, open val processingAudience: List<EncryptionProtos.Audience>) {
    fun processingKeysTransient(objectMapper: ObjectMapper): Map<String, ByteArray> {
        return mapOf(ProvenanceDIME.PROCESSING_KEYS to objectMapper.writeValueAsString(this.processingAudience).toByteArray(Charsets.UTF_8))
    }
}

data class DIMEStreamProcessingModel(override val dime: EncryptionProtos.DIME, override val processingAudience: List<Audience>, val encryptedPayload: InputStream):
    DIMEProcessingModel(dime, processingAudience)

data class DIMEAdditionalAuthenticationModel(val dekAdditionalAuthenticatedData: String = "", val payloadAdditionalAuthenticatedData: String = "")
data class DIMEDekPayloadModel(val dek: String, val decryptedPayload: String) {
    fun getSecretKeySpec(): SecretKeySpec = ProvenanceAESCrypt.secretKeySpecGenerate(Base64.getDecoder().decode(dek))
}
