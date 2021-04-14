package io.p8e.crypto

import com.fortanix.sdkms.v1.ApiClient
import com.fortanix.sdkms.v1.Configuration
import com.fortanix.sdkms.v1.api.AuthenticationApi
import com.fortanix.sdkms.v1.api.SignAndVerifyApi
import com.fortanix.sdkms.v1.auth.ApiKeyAuth
import com.fortanix.sdkms.v1.model.DigestAlgorithm
import com.fortanix.sdkms.v1.model.SignRequest
import com.fortanix.sdkms.v1.model.VerifyRequest
import com.google.protobuf.Message
import io.p8e.proto.Common.Signature
import io.p8e.util.orThrow
import io.p8e.util.toProtoUuidProv
import java.lang.IllegalStateException
import java.security.KeyPair

class SmartKeySigner(
    appApiKey: String
): SignerImpl() {
    private var sObjUUID: String = ""
    private val apiKey = appApiKey

    init {
        val client = ApiClient()
        client.setBasicAuthString(apiKey)
        Configuration.setDefaultApiClient(client)

        val authResponse = AuthenticationApi().authorize()
        val auth = client.getAuthentication("bearerToken") as ApiKeyAuth
        auth.apiKey = authResponse.accessToken
        auth.apiKeyPrefix = "Bearer"
    }

    override fun setKeyId(keyPair: KeyPair) {
       /*no-op*/
    }

    override fun setKeyId(uuid: String) {
        sObjUUID = uuid
    }

    override fun sign(data: String): Signature = sign(data.toByteArray())

    override fun sign(data: Message): Signature = sign(data.toByteArray())

    override fun sign(data: ByteArray): Signature {
        val signatureRequest = SignRequest()
            .hashAlg(DigestAlgorithm.SHA512)
            .data(data)
            .deterministicSignature(true)

        val signatureResponse = SignAndVerifyApi().sign(sObjUUID, signatureRequest)

        return Signature.newBuilder()
            .setAlgo(DigestAlgorithm.SHA512.value)
            .setProvider("SmartKey")
            .setSignature(signatureResponse.signature.toString())
            .setKeyId(signatureResponse.kid.toProtoUuidProv())
            .build()
            .takeIf { verify(data, it) }
            .orThrow { IllegalStateException("Invalid signature") }
    }

    private fun verify(data: String, signature: Signature): Boolean = verify(data, signature)

    private fun verify(data: ByteArray, signature: Signature): Boolean {
        val sigVerificationRequest = VerifyRequest()
            .hashAlg(DigestAlgorithm.SHA512)
            .data(data)
            .signature(signature.signature.toByteArray())

        return SignAndVerifyApi().verify(signature.keyId.value, sigVerificationRequest).result
    }

}
