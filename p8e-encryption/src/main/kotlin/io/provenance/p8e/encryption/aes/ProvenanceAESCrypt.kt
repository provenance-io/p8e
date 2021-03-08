package io.provenance.p8e.encryption.aes

import io.provenance.p8e.encryption.CryptoException
import io.provenance.p8e.encryption.ProvenanceIvVectorException
import io.provenance.p8e.encryption.util.ByteUtil
import io.provenance.p8e.encryption.util.EncryptedInputStream
import io.provenance.p8e.encryption.util.HashingCipherInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ProvenanceAESCrypt {

//    private val secureRandom = SecureRandom.getInstanceStrong()

    /**
     * Password based encryption using AES - GCM 256 bits.
     * additionalAuthenticatedData --AAD
     */
    fun encrypt(plainText: ByteArray, additionalAuthenticatedData: String? = "", key: SecretKeySpec, useZeroIV: Boolean = false): ByteArray? {

        try {
            // Generate iv - each encryption call has a different iv.
            val iv = ByteArray(BLOCK_LENGTH)
            if (!useZeroIV) {
                SecureRandom().nextBytes(iv)
            }

            // Encrypt using AES-GCM
            //using Nopadding based on this...https://stackoverflow.com/questions/31248777/can-pkcs5padding-be-in-aes-gcm-mode
            val cipher = Cipher.getInstance(ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            //key - the key material of the secret key.
            // The contents of the array are copied to protect against subsequent modification.
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)

            //add associated data
            additionalAuthenticatedData?.let { cipher.updateAAD(additionalAuthenticatedData.toByteArray(Charset.forName("UTF-8"))) }

            val encryptedCipherText = cipher.doFinal(plainText)
            val byteBuffer = ByteBuffer.allocate(4 + iv.size + encryptedCipherText.size)
            byteBuffer.putInt(iv.size)
            byteBuffer.put(iv)
            byteBuffer.put(encryptedCipherText)
            return byteBuffer.array()

        } catch (e: Exception) {
            throw CryptoException("Could not encrypt bytes.", e)
        }
    }

    /**
     * Password based encryption using AES - GCM 256 bits.
     * additionalAuthenticatedData --AAD
     */
    fun encrypt(inputStream: InputStream, additionalAuthenticatedData: String? = "", key: SecretKeySpec, useZeroIV: Boolean = false): InputStream {
        try {
            val iv = ByteArray(BLOCK_LENGTH)
            if (!useZeroIV) {
                SecureRandom().nextBytes(iv)
            }

            // Encrypt using AES-GCM
            //using Nopadding based on this...https://stackoverflow.com/questions/31248777/can-pkcs5padding-be-in-aes-gcm-mode
            val cipher = Cipher.getInstance(ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            //key - the key material of the secret key.
            // The contents of the array are copied to protect against subsequent modification.
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)

            //add associated data
            additionalAuthenticatedData?.let { cipher.updateAAD(additionalAuthenticatedData.toByteArray(Charset.forName("UTF-8"))) }

            val header = ByteBuffer.allocate(4 + iv.size)
            header.putInt(iv.size)
            header.put(iv)

            return HashingCipherInputStream(inputStream, cipher, MessageDigest.getInstance("SHA-512"), header.array())
        } catch (t: Throwable) {
            throw CryptoException("Could not encrypt InputStream.", t)
        }
    }

    /**
     * Generate Secret KeySpec
     */
    fun secretKeySpecGenerate(): SecretKeySpec {
        val keyBytes = ByteArray(ProvenanceAESCrypt.BLOCK_LENGTH_KEY)
        try {
            SecureRandom().nextBytes(keyBytes)
            val key = SecretKeySpec(keyBytes, "AES")
            return key
        } finally {
            //avoid storing it in memory till garbage collection
            Arrays.fill(keyBytes, 0.toByte()) //overwrite the content of key with zeros
        }
    }


    /**
     * Decrypt bytes previously encrypted with this class.
     *
     * @param dataToDecrypt    The data to decrypt
     * @param key              The AES key to use for decryption
     * @param additionalAuthenticatedData         Additional data, mandatory for provenance
     * @return                 The decrypted bytes
     * @throws                 CryptoException if bytes could not be decrypted
     */
    @Throws(CryptoException::class, ProvenanceIvVectorException::class)
    fun decrypt(dataToDecrypt: ByteArray, key: ByteArray, additionalAuthenticatedData: String? = "", useZeroIV: Boolean = false): ByteArray {
        if (dataToDecrypt.isEmpty()) {
            // job done
            return dataToDecrypt
        }

        val iv: ByteArray
        try {
            val byteBuffer = ByteBuffer.wrap(dataToDecrypt)

            if (!useZeroIV) {

                val ivLength = byteBuffer.int

                //here for node js interoperability ..should be removed soon..
                //println("The key is ${BaseEncoding.base64().encode(key)}")
                //println("The data to decrypt is ${BaseEncoding.base64().encode(dataToDecrypt)}")

                if (ivLength < 12 || ivLength >= 16) { // check input parameter
                    throw ProvenanceIvVectorException("invalid iv length")
                }

                iv = ByteArray(ivLength)
            } else {
                iv = ByteArray(BLOCK_LENGTH)
            }
            byteBuffer.get(iv)
            val cipherText = ByteArray(byteBuffer.remaining())
            byteBuffer.get(cipherText)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BIT, iv))
            additionalAuthenticatedData?.let { cipher.updateAAD(additionalAuthenticatedData.toByteArray()) }

            return cipher.doFinal(cipherText)
        } catch (e: ProvenanceIvVectorException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("Could not decrypt bytes", e)
        }
    }

    /**
     * Decrypt bytes previously encrypted with this class.
     *
     * @param inputStream      The data to decrypt
     * @param key              The AES key to use for decryption
     * @param additionalAuthenticatedData         Additional data, mandatory for provenance
     * @return                 The decrypted bytes
     * @throws                 CryptoException if bytes could not be decrypted
     */
    @Throws(CryptoException::class, ProvenanceIvVectorException::class)
    fun decrypt(inputStream: InputStream, key: ByteArray, additionalAuthenticatedData: String? = "", useZeroIV: Boolean = false): InputStream {
        try {
            val iv = if (useZeroIV) {
                ByteArray(BLOCK_LENGTH)
            } else {
                val ivLengthBytes = ByteArray(4)
                if (inputStream.read(ivLengthBytes) < 0) {
                    // Empty inputStream is already decrypted
                    return inputStream
                }
                val ivLength = ByteUtil.getUInt32(ivLengthBytes)

                if (ivLength < 12 || ivLength >= 16) { // check input parameter
                    throw ProvenanceIvVectorException("invalid iv length")
                }
                val iv = ByteArray(ivLength.toInt())
                inputStream.read(iv)
                iv
            }

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BIT, iv))
            additionalAuthenticatedData?.let { cipher.updateAAD(additionalAuthenticatedData.toByteArray()) }
            return HashingCipherInputStream(inputStream, cipher, MessageDigest.getInstance(SHA_512))
        } catch(t: Throwable) {
            throw CryptoException("Could not decrypt InputStream", t)
        }
    }

    /**
     * The size of an AES block in bytes.
     * This is also the length of the initialisation vector.
     */
    private val BLOCK_LENGTH = 12  // = 96 bits.
    //Length of the Key
    private val BLOCK_LENGTH_KEY = 32  // = 96 bits.
    //AAD length
    private val TAG_LENGTH_BIT = 128
    private val ALGORITHM = "AES/GCM/NoPadding"
    val SHA_512 = "SHA-512"

    /**
     * Generate Secret KeySpec
     */
    fun secretKeySpecGenerate(keyBytes: ByteArray): SecretKeySpec {
        try {
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            //avoid storing it in memory till garbage collection
            Arrays.fill(keyBytes, 0.toByte()) //overwrite the content of key with zeros
        }
    }

}
