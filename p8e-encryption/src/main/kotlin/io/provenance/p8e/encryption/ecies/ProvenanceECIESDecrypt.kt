package io.provenance.p8e.encryption.ecies

import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt
import io.provenance.p8e.encryption.kdf.ProvenanceHKDFSHA256
import org.slf4j.LoggerFactory
import java.security.InvalidKeyException
import java.security.PrivateKey
import java.util.*
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

class ProvenanceECIESDecrypt(private val privateKey: PrivateKey,
                             private val additionalAuthenticatedData: String? = "") {

    private val logger = LoggerFactory.getLogger(this.javaClass)

       /**
     * Decrypt provided encrypted payload.
     *
     * @param payload {@code ProvenanceECIESCryptogram} Payload to be decrypted.
     * @return Decrypted bytes.
     * @throws ProvenanceECIESDecryptException In case decryption fails due to invalid key or invalid MAC value.
     * @throws IllegalArgumentException In case decryption fails due to invalid life-cycle phase,
     */
    @Throws(ProvenanceECIESDecryptException::class)
    fun decrypt(payload: ProvenanceECIESCryptogram): ByteArray {
        try {
            // Derive secret key
            val secretKey = ProvenanceKeyGenerator.computeSharedKey(privateKey, payload.ephemeralPublicKey)
            val ephemeralDerivedSecretKey = ProvenanceHKDFSHA256.derive(ECUtils.convertSharedSecretKeyToBytes(secretKey), null,
                    ECUtils.KDF_SIZE)

            // Validate data MAC value
            val encKeyBytes = Arrays.copyOf(ephemeralDerivedSecretKey, 32)
            val encKey = ProvenanceAESCrypt.secretKeySpecGenerate(encKeyBytes)
            val macKeyBytes = Arrays.copyOfRange(ephemeralDerivedSecretKey, 32, 64)
            val mac = ProvenanceAESCrypt.encrypt(macKeyBytes, additionalAuthenticatedData = additionalAuthenticatedData, key = encKey, useZeroIV = true)
            if (!Arrays.equals(mac, payload.tag)) {
                logger.error("invalid mac ", IllegalArgumentException("Invalid MAC"))
                throw ProvenanceECIESDecryptException("Invalid MAC", IllegalArgumentException("Invalid MAC"))
            }

            // Decrypt the data
            return ProvenanceAESCrypt.decrypt(payload.encryptedData, encKey.encoded, additionalAuthenticatedData)
        } catch (e: InvalidKeyException) {
            logger.error("invalid key ", e)
            throw ProvenanceECIESDecryptException("Decryption error occurred", e)
        } catch (e: IllegalBlockSizeException) {
            logger.error("Illegal block size ", e)
            throw ProvenanceECIESDecryptException("Decryption error occurred", e)
        } catch (e: BadPaddingException) {
            logger.error("Bad padding ", e)
            throw ProvenanceECIESDecryptException("Decryption error occurred", e)
        }
    }

}
