package io.provenance.p8e.encryption.ecies

import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator.SupportedKeyAgreementAlgorithm.*
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey

object ProvenanceKeyGenerator {
    val logger = LoggerFactory.getLogger(ProvenanceKeyGenerator::class.java)

    init {
        // Initialize the security provider if the ecies package is accessed.
        Security.addProvider(BouncyCastleProvider())

        // Init each algorithm to determine if there are any exceptions in the provider setup.
        SupportedKeyAgreementAlgorithm.values().map {
            getKeyAgreement(it)
            getKeyGenerator(it)
        }
    }

    enum class SupportedKeyAgreementAlgorithm {
        ECDH,  // ECUtils
        XDH    // EDUtils
    }

    /**
     * Generate a new ECDH key pair using the same curve as the given public key.
     *
     * @return A new key pair instance, or null in case of an error.
     */
    fun generateKeyPair(withSameCurveAs: PublicKey) = withSameCurveAs.let {
        // Handle ECPublicKey's differently since they have additional parameters that we need to use
        // when initializing the key generator.
        if (it is ECPublicKey)
            return getKeyGenerator().apply {
                initialize(it.params)
            }.tryGenerateKeyPair()
        else
            ProvenanceKeyGenerator.generateKeyPair(it.algorithm)
    }


    /**
     * Generate a new ECDH key pair using specified curve.
     *
     * @return A new key pair instance, or null in case of an error.
     */
    @JvmOverloads
    fun generateKeyPair(curve: String = ECUtils.LEGACY_DIME_CURVE): KeyPair {
        var ed25519 = EDUtils.KNOWN_CURVES.contains(curve)

        if (!(ECUtils.RECOMMENDED_CURVES.contains(curve) || ed25519)) {
            logger.warn("Selected curve - ${curve} is not one of the recommended curves.")
        }

        if (!(ECUtils.KNOWN_CURVES.contains(curve) || ed25519)) {
            logger.warn("Selected curve is not one of the KNOWN_CURVES. Undefined behavior may result.")
        }

        // ED25519 keys need to be generated with a different algorithm.
        if (ed25519)
            return EDUtils.generateKeyPair()
        else
            return getKeyGenerator().apply {
              initialize(ECGenParameterSpec(curve))
            }.tryGenerateKeyPair()
    }

    /**
     * Computes a pre-shared key for given private key and public key.
     *
     * @param privateKey A private key.
     * @param publicKey A public key.
     * @return A new instance of the pre-shared key.
     * @throws InvalidKeyException One of the provided keys are not valid keys.
     */
    @Throws(InvalidKeyException::class)
    fun computeSharedKey(privateKey: PrivateKey, publicKey: PublicKey): SecretKey {
        // Determine the correct exchange algorithm and convert keys to the appropriate types.
        val algorithm: SupportedKeyAgreementAlgorithm
        val pub:PublicKey
        val priv: PrivateKey
        if (EDUtils.KNOWN_CURVES.contains(privateKey.algorithm)) {
            algorithm = SupportedKeyAgreementAlgorithm.XDH

            // ED25519 can be converted to X25519 to support key agreements.
            EDUtils.toX25519(KeyPair(publicKey, privateKey)).also {
                pub = it.public
                priv = it.private
            }
        } else {
            algorithm = SupportedKeyAgreementAlgorithm.ECDH
            pub = publicKey
            priv = privateKey
        }

        try {
            return getKeyAgreement(algorithm).let {
                it.init(priv)
                it.doPhase(pub, true)
                ProvenanceAESCrypt.secretKeySpecGenerate(it.generateSecret())
            }
        } catch (ex: NoSuchAlgorithmException) {
            throw ex
        } catch (ex: NoSuchProviderException) {
            throw ex
        }
    }

    private fun getKeyAgreement(algorithm: SupportedKeyAgreementAlgorithm = ECDH) =
        KeyAgreement.getInstance(algorithm.toString(), Security.getProvider(BouncyCastleProvider.PROVIDER_NAME))

    private fun getKeyGenerator(algorithm: SupportedKeyAgreementAlgorithm = ECDH, provider: String = BouncyCastleProvider.PROVIDER_NAME) =
            KeyPairGenerator.getInstance(algorithm.toString(), Security.getProvider(provider))

    private fun KeyPairGenerator.tryGenerateKeyPair(): KeyPair {
        try {
            return this.generateKeyPair()
        } catch (ex: NoSuchAlgorithmException) {
            logger.error("No such algorithm for ${this.algorithm}", ex)
            throw ex
        } catch (ex: NoSuchProviderException) {
            logger.error("No such provider...add bouncy castle as a security provider", ex)
            throw ex
        } catch (ex: InvalidAlgorithmParameterException) {
            logger.error("Invalid algorithm ${this.algorithm}", ex)
            throw ex
        }
    }

    /**
     * Generate a new random byte array with given length.
     *
     * @param len Number of random bytes to be generated.
     * @return An array with len random bytes.
     */
    fun generateRandomBytes(len: Int) =
        ByteArray(len).apply {
            SecureRandom().nextBytes(this)
        }
}



