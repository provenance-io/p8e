package io.p8e.crypto

import com.fortanix.sdkms.v1.api.SecurityObjectsApi
import com.fortanix.sdkms.v1.api.SignAndVerifyApi
import com.fortanix.sdkms.v1.model.DigestAlgorithm
import com.fortanix.sdkms.v1.model.SignRequest
import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl.Companion.DEFAULT_HASH
import io.p8e.crypto.SignerImpl.Companion.PROVIDER
import io.p8e.proto.Common
import io.p8e.proto.PK
import io.p8e.proto.ProtoUtil
import io.p8e.util.base64Decode
import io.p8e.util.base64Encode
import io.p8e.util.orThrow
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toPublicKeyProto
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.lang.IllegalStateException
import java.security.PublicKey
import java.security.Security
import java.security.Signature

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
    private val keyUuid: String,
    private val publicKey: PublicKey,
    private val signAndVerifyApi: SignAndVerifyApi
): SignerImpl {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    private var signature: Signature? = null
    private var signatureRequest: SignRequest? = null

    private var verifying: Boolean = false

    override var hashType: SignerImpl.Companion.HashType = DEFAULT_HASH
    override var deterministic: Boolean = true
    private val digestAlgorithm: DigestAlgorithm
        get() = when(hashType) {
            SignerImpl.Companion.HashType.SHA256 -> DigestAlgorithm.SHA256
            SignerImpl.Companion.HashType.SHA512 -> DigestAlgorithm.SHA512
        }

    /**
     * Using the local java security signature instance to verify data.
     */
    override fun initVerify(publicKey: PublicKey) {
        signature = Signature.getInstance(signAlgorithm, PROVIDER).apply { initVerify(publicKey) }
        verifying = true
    }

    /**
     * Using SmartKey to sign data.
     */
    override fun initSign() {
        signatureRequest = SignRequest()
            .hashAlg(digestAlgorithm)
            .deterministicSignature(deterministic)
            .data(byteArrayOf())

        verifying = false
    }

    override fun update(data: ByteArray, off: Int, res: Int) {
        if(!verifying) {
            signatureRequest?.data = data.copyOfRange(off, res)
        } else {
            signature?.update(data, off, res)
        }
    }

    override fun verify(signatureBytes: ByteArray): Boolean = signature?.verify(signatureBytes)!!

    override fun sign(): ByteArray = signAndVerifyApi.sign(keyUuid, signatureRequest).signature

    override fun sign(data: String): Common.Signature = sign(data.toByteArray())

    override fun sign(data: Message): Common.Signature = sign(data.toByteArray())

    override fun sign(data: ByteArray): Common.Signature {
        val signatureRequest = SignRequest()
            .hashAlg(digestAlgorithm)
            .data(data)
            .deterministicSignature(deterministic)

        val signatureResponse = signAndVerifyApi.sign(keyUuid, signatureRequest)

        return ProtoUtil
            .signatureBuilderOf(String(signatureResponse.signature.base64Encode()), signAlgorithm)
            .setSigner(signer())
            .build()
            .takeIf { verify(publicKey!!, data, it) }
            .orThrow { IllegalStateException("can't verify signature - public cert may not match private key.") }
    }

    override fun signer(): PK.SigningAndEncryptionPublicKeys =
        PK.SigningAndEncryptionPublicKeys.newBuilder()
            .setSigningPublicKey(
                getPublicKey().toPublicKeyProto()
            ).build()

    override fun update(data: ByteArray) {
        if(!verifying) {
            signatureRequest?.data(data)
        } else {
            signature?.update(data)
        }
    }

    override fun update(data: Byte) {
        val dataByteArray = mutableListOf(data)
        if(!verifying) {
            signatureRequest?.data(dataByteArray.toByteArray())
        } else {
            signature?.update(data)
        }
    }

    override fun verify(publicKey: PublicKey, data: ByteArray, signature: Common.Signature): Boolean {
        val s = Signature.getInstance(signature.algo, signature.provider)
        s.initVerify(publicKey)
        s.update(data)
        return s.verify(signature.signature.base64Decode())
    }

    /**
     * Return the public used to setup the signer.
     */
    override fun getPublicKey(): PublicKey = publicKey!!
}
