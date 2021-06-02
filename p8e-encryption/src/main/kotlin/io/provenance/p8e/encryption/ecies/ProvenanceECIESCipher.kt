package io.provenance.p8e.encryption.ecies

import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt
import io.provenance.p8e.encryption.experimental.extensions.toAgreeKey
import io.provenance.p8e.encryption.kdf.ProvenanceHKDFSHA256
import org.slf4j.LoggerFactory
import java.security.InvalidKeyException
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

class ProvenanceECIESCipher {

    private var logger = LoggerFactory.getLogger(ProvenanceECIESCipher::class.java)

    /**
     * Encrypt data using ECIES with instance public key and additional info for KDF function.
     * @param data Data to be encrypted.
     * @return ProvenanceECIESCryptogram
     * @throws ProvenanceECIESEncryptException In case data encryption fails due to invalid key.
     * @throws IllegalArgumentException If ProvenanceECIESEncrypt has already been used..
     * @throws IllegalArgumentException If Unable to generate an ephemeral key pair
     */
    @Throws(ProvenanceECIESEncryptException::class, IllegalArgumentException::class)
    fun encrypt(data: ByteArray, publicKey: PublicKey, additionalAuthenticatedData: String?): ProvenanceECIESCryptogram {
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

    /**
     * SmartKey Version
     */
    @Throws(ProvenanceECIESEncryptException::class, IllegalArgumentException::class)
    fun encrypt(data: ByteArray, publicKey: PublicKey, kid: String, additionalAuthenticatedData: String?): ProvenanceECIESCryptogram {
        try {
            val ephemeralKeyPair = ProvenanceKeyGenerator.generateKeyPair(publicKey)

            val agreeKey = kid.toAgreeKey()
            val ephemeralDerivedSecretKey =
                agreeKey.kcv.let { ProvenanceHKDFSHA256.derive(it.encodeToByteArray(), null, ECUtils.KDF_SIZE) }

            // Encrypt the data
            val encKeyBytes = Arrays.copyOf(ephemeralDerivedSecretKey, 32)
            val encKey = ProvenanceAESCrypt.secretKeySpecGenerate(encKeyBytes)
            val body = ProvenanceAESCrypt.encrypt(data, additionalAuthenticatedData, encKey)

            // Compute MAC of the data
            val macKeyBytes = Arrays.copyOfRange(ephemeralDerivedSecretKey, 32, 64)

            val tag = ProvenanceAESCrypt.encrypt(macKeyBytes, additionalAuthenticatedData, encKey, true)

            return ProvenanceECIESCryptogram(ephemeralKeyPair.public, tag = tag!!, encryptedData = body!!)
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

    /**
     * Decrypt provided encrypted payload.
     *
     * @param payload {@code ProvenanceECIESCryptogram} Payload to be decrypted.
     * @return Decrypted bytes.
     * @throws ProvenanceECIESDecryptException In case decryption fails due to invalid key or invalid MAC value.
     * @throws IllegalArgumentException In case decryption fails due to invalid life-cycle phase,
     */
    @Throws(ProvenanceECIESDecryptException::class)
    fun decrypt(payload: ProvenanceECIESCryptogram, privateKey: PrivateKey, additionalAuthenticatedData: String?): ByteArray {
        try {
            // Derive secret key
            val secretKey = ProvenanceKeyGenerator.computeSharedKey(privateKey, payload.ephemeralPublicKey!!)
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

    // SmartKey version
    fun decrypt(payload: ProvenanceECIESCryptogram, kid: String, additionalAuthenticatedData: String?): ByteArray {
        try {
            //Key Checksum Value (KCV) is the checksum of a cryptographic key.[1] It is used to validate the key integrity or compare keys without knowing their actual values
            val ephemeralDerivedSecretKey =
                ProvenanceHKDFSHA256.derive(kid.toAgreeKey().kcv.encodeToByteArray(), null, ECUtils.KDF_SIZE)

            val encKeyBytes = Arrays.copyOf(ephemeralDerivedSecretKey, 32)
            val encKey = ProvenanceAESCrypt.secretKeySpecGenerate(encKeyBytes)
            val macKeyBytes = Arrays.copyOfRange(ephemeralDerivedSecretKey, 32, 64)
            val mac = ProvenanceAESCrypt.encrypt(macKeyBytes, additionalAuthenticatedData, encKey, true)
            if (!Arrays.equals(mac, payload.tag)) {
                logger.error("invalid mac ", IllegalArgumentException("Invalid MAC"))
                throw ProvenanceECIESDecryptException("Invalid MAC", IllegalArgumentException("Invalid MAC"))
            }
            return ProvenanceAESCrypt.decrypt(payload.encryptedData, encKey.encoded, additionalAuthenticatedData)
        }  catch (e: InvalidKeyException) {
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
