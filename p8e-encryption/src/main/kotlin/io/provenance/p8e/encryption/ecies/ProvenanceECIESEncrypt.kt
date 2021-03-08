package io.provenance.p8e.encryption.ecies

import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt
import io.provenance.p8e.encryption.kdf.ProvenanceHKDFSHA256
import org.slf4j.LoggerFactory
import java.security.InvalidKeyException
import java.security.PublicKey
import java.util.*
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

class ProvenanceECIESEncrypt(private val publicKey: PublicKey,
                             private val additionalAuthenticatedData: String? = "") {

    private var logger = LoggerFactory.getLogger(ProvenanceECIESEncrypt::class.java)

    /**
     * Encrypt data using ECIES with instance public key and additional info for KDF function.
     * @param data Data to be encrypted.
     * @return ProvenanceECIESCryptogram
     * @throws ProvenanceECIESEncryptException In case data encryption fails due to invalid key.
     * @throws IllegalArgumentException If ProvenanceECIESEncrypt has already been used..
     * @throws IllegalArgumentException If Unable to generate an ephemeral key pair
     */
    @Throws(ProvenanceECIESEncryptException::class, IllegalArgumentException::class)
    fun encrypt(data: ByteArray): ProvenanceECIESCryptogram {
        try {
            val ephemeralKeyPair = ProvenanceKeyGenerator.generateKeyPair(publicKey)

            // Derive a secret key from the public key of the recipient and the ephemeral private key.
            val ephemeralDerivedSecretKey = ProvenanceKeyGenerator.computeSharedKey(ephemeralKeyPair.private, publicKey).let {
                ProvenanceHKDFSHA256.derive(it.encoded,null, ECUtils.KDF_SIZE)
            }

            // Encrypt the data
            val encKeyBytes = Arrays.copyOf(ephemeralDerivedSecretKey, 32)
            val encKey = ProvenanceAESCrypt.secretKeySpecGenerate(encKeyBytes)
            val body = ProvenanceAESCrypt.encrypt(data, additionalAuthenticatedData, encKey)

            // Compute MAC of the data
            val macKeyBytes = Arrays.copyOfRange(ephemeralDerivedSecretKey, 32, 64)

            val tag = ProvenanceAESCrypt.encrypt(plainText = macKeyBytes!!, additionalAuthenticatedData = additionalAuthenticatedData, key = encKey, useZeroIV = true)

            // Return encrypted payload
            return ProvenanceECIESCryptogram(ephemeralKeyPair.public!!, tag!!, body!!)
        } catch (e: InvalidKeyException) {
            logger.error("Invalid key exception", e)
            throw ProvenanceECIESEncryptException("Decryption error occurred", e)
        } catch (e: BadPaddingException) {
            logger.error("Bad padding ", e)
            throw ProvenanceECIESEncryptException("Decryption error occurred", e)
        } catch (e: IllegalBlockSizeException) {
            logger.error("Illegal block size ", e)
            throw ProvenanceECIESEncryptException("Decryption error occurred", e)
        }
    }
}
