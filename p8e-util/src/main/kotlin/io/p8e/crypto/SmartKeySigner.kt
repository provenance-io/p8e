package io.p8e.crypto

import com.fortanix.sdkms.v1.ApiClient
import com.fortanix.sdkms.v1.Configuration
import com.fortanix.sdkms.v1.api.AuthenticationApi
import com.fortanix.sdkms.v1.api.SecurityObjectsApi
import com.fortanix.sdkms.v1.api.SignAndVerifyApi
import com.fortanix.sdkms.v1.auth.ApiKeyAuth
import com.fortanix.sdkms.v1.model.DigestAlgorithm
import com.fortanix.sdkms.v1.model.SignRequest
import com.fortanix.sdkms.v1.model.VerifyKcvRequest
import com.fortanix.sdkms.v1.model.VerifyRequest
import com.google.protobuf.Message
import io.p8e.proto.Common
import io.p8e.util.orThrow
import io.p8e.util.toHex
import io.p8e.util.toJavaPublicKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import java.lang.IllegalStateException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * About SmartKey
 *
 * SmartKey™ powered by Fortanix is the world’s first cloud service secured with Intel® SGX. With SmartKey, you can
 * securely generate, store, and use cryptographic keys and certificates, as well as other secrets such as passwords,
 * API keys, tokens, or any blob of data. Your business-critical applications and containers can integrate with
 * SmartKey using legacy cryptographic interfaces or using the native SmartKey RESTful interface.
 *
 * SmartKey uses built-in cryptography in Intel® Xeon® CPUs to help protect the customer’s keys and data from all
 * external agents, reducing the system complexity greatly by removing reliance on characteristics of the physical
 * boxes. Intel® SGX enclaves prevent access to customer’s keys or data by Equinix, Fortanix or any other cloud service
 * provider.
 *
 * Unlike many hardware security technologies, Intel® SGX is designed to help protect arbitrary x86 program code.
 * SmartKey uses Intel® SGX not only to help protect the keys and data but also all the application logic including
 * role based access control, account set up, and password recovery. The result is significantly improved security
 * for a key management service that offers the elasticity of modern cloud software and the hardware-based security
 * of an HSM appliance, all while drastically reducing initial and ongoing costs.
 *
 * SmartKey is designed to enable businesses to serve key management needs for all their applications, whether they are
 * operating in a public, private, or hybrid cloud.
 */

class SmartKeySigner(
    private val appApiKey: String,
    private val keyUuid: String
): SignerImpl {

    init {
        val client = ApiClient()
        client.setBasicAuthString(appApiKey)
        Configuration.setDefaultApiClient(client)

        val authResponse = AuthenticationApi().authorize()
        val auth = client.getAuthentication("bearerToken") as ApiKeyAuth
        auth.apiKey = authResponse.accessToken
        auth.apiKeyPrefix = "Bearer"
    }

    override fun sign(data: String): Common.Signature = sign(data.toByteArray())

    override fun sign(data: Message): Common.Signature = sign(data.toByteArray())

    override fun sign(data: ByteArray): Common.Signature {
        val signatureRequest = SignRequest()
            .hashAlg(DigestAlgorithm.SHA512)
            .hash(data)
            .deterministicSignature(true)

        val signatureResponse = SignAndVerifyApi().sign(keyUuid, signatureRequest)

        return Common.Signature.newBuilder()
            .setAlgo(DigestAlgorithm.SHA512.value)
            .setProvider("SmartKey")
            .setSignature(signatureResponse.signature.toString())
            .build()
            .takeIf { verify(data, signatureResponse.signature) }
            .orThrow { IllegalStateException("Invalid signature") }
    }

    override fun sign(): ByteArray {
        TODO("Not yet implemented")
    }

    override fun update(data: Byte) {
        // Not needed for SmartKey
    }

    override fun update(data: ByteArray, off: Int, len: Int) {
        // Not needed for SmartKey
    }

//    override fun verify(data: ByteArray): Boolean {
//        val sigVerificationRequest = VerifyRequest()
//            .hashAlg(DigestAlgorithm.SHA512)
//            .hash(data)
//            .signature(data)
//    }

    override fun verify(data: ByteArray, signature: Common.Signature): Boolean {
        TODO("Not yet implemented")
    }

    /**
     * Get and convert SmartKey's public key (Sun Security Provider) into a BouncyCastle Provider (P8e).
     *
     * @return [PublicKey] return the Java security version of the PublicKey.
     */
    override fun getPublicKey(): PublicKey {
        val smPublicKey = SecurityObjectsApi().getSecurityObject(keyUuid).pubKey
        val x509PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(smPublicKey))
        val bcPublicKey = BCECPublicKey(x509PublicKey as ECPublicKey, BouncyCastlePQCProvider.CONFIGURATION)
        return bcPublicKey.toHex().toJavaPublicKey()
    }

   // override fun verify(data: ByteArray, signature: Signature): Boolean = verify(data, signature)

    private fun verify(data: String, signature: Signature): Boolean = verify(data, signature)

    override fun verify(data: ByteArray, signature: ByteArray): Boolean {
        //TODO: Investigate local signature verification verification.
        val sigVerificationRequest = VerifyRequest()
            .hashAlg(DigestAlgorithm.SHA512)
            .hash(data)
            .signature(signature)

        return SignAndVerifyApi().verify(keyUuid, sigVerificationRequest).result
    }
}
