package io.p8e.crypto

import com.fortanix.sdkms.v1.api.SecurityObjectsApi
import com.fortanix.sdkms.v1.api.SignAndVerifyApi
import com.fortanix.sdkms.v1.model.DigestAlgorithm
import com.fortanix.sdkms.v1.model.SignRequest
import com.google.protobuf.Message
import io.p8e.proto.Common
import io.p8e.proto.PK
import io.p8e.proto.ProtoUtil
import io.p8e.util.base64Decode
import io.p8e.util.base64Encode
import io.p8e.util.orThrow
import io.p8e.util.toHex
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toPublicKeyProto
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import java.lang.IllegalStateException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Security
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

class SmartKeySigner: SignerImpl {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    companion object {
        // Algo must match Provenance-object-store
        val SIGN_ALGO = "SHA512withECDSA"
        val PROVIDER = BouncyCastleProvider.PROVIDER_NAME
    }

    var signature: Signature? = null

    private var signatureRequest: SignRequest? = null
    private var verifying: Boolean = false
    private var keyUuid: String? = null

    private val cachedData = mutableListOf<ByteArray>()

    fun instance(keyUuid: String): SmartKeySigner {
        this.keyUuid = keyUuid
        return this
    }

    /**
     * Using the local java security signature instance to verify data.
     */
    override fun initVerify(publicKey: PublicKey) {
        signature = Signature.getInstance(SIGN_ALGO, PROVIDER).apply { initVerify(publicKey) }
        verifying = true
    }

    /**
     * Using SmartKey to sign data.
     */
    override fun initSign() {
        signatureRequest = SignRequest().hashAlg(DigestAlgorithm.SHA512).deterministicSignature(true)
        verifying = false
    }

    override fun update(data: ByteArray, off: Int, len: Int) {
        if(!verifying) {
            signatureRequest?.data(data.copyOfRange(off, off+len))
        } else {
            cachedData.add(data.copyOfRange(off, off+len))
        }
    }

    override fun verify(signatureBytes: ByteArray): Boolean {
        // take the last piece of data cached to verify against.
        signature?.update(cachedData.last())

        return signature?.verify(signatureBytes)!!
    }

    override fun sign(): ByteArray = SignAndVerifyApi().sign(keyUuid, signatureRequest).signature

    override fun sign(data: String): Common.Signature = sign(data.toByteArray())

    override fun sign(data: Message): Common.Signature = sign(data.toByteArray())

    override fun sign(data: ByteArray): Common.Signature {
        val signatureRequest = SignRequest()
            .hashAlg(DigestAlgorithm.SHA512)
            .data(data)
            .deterministicSignature(true)

        val signatureResponse = SignAndVerifyApi().sign(keyUuid, signatureRequest)

        return ProtoUtil
            .signatureBuilderOf(String(signatureResponse.signature.base64Encode()))
            .setSigner(signer())
            .build()
            .takeIf { verify(data, it) }
            .orThrow { IllegalStateException("can't verify signature - public cert may not match private key.") }
    }

    override fun signer(): PK.SigningAndEncryptionPublicKeys =
        PK.SigningAndEncryptionPublicKeys.newBuilder()
            .setSigningPublicKey(
                getPublicKey().toPublicKeyProto()
            ).build()

    override fun update(data: ByteArray) { signature?.update(data) }

    override fun update(data: Byte) { signature?.update(data) }

    override fun verify(data: ByteArray, signature: Common.Signature): Boolean {
        val s = Signature.getInstance(signature.algo, signature.provider)
        s.initVerify(getPublicKey())
        s.update(data)
        return s.verify(signature.signature.base64Decode())
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
}
