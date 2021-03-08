package io.provenance.p8e.encryption.experimental.aes

import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt.SHA_512
import io.provenance.p8e.encryption.util.ByteUtil
import io.provenance.p8e.encryption.util.HashingCipherInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Cipher.DECRYPT_MODE
import javax.crypto.Cipher.ENCRYPT_MODE
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val BLOCK_LENGTH = 12  // = 96 bits.
private const val TAG_LENGTH_BIT = 128
private const val CRYPTO_ALGORITHM = "AES/GCM/NoPadding"

/**
 * Generate an empty initialization vector for the symmetric encryption
 */
fun emptyIv() = ByteArray(BLOCK_LENGTH)

/**
 * Generate a random initialization vector for the symmetric encryption
 */
fun randomIv() = emptyIv().also { SecureRandom().nextBytes(it) }

/**
 * Symmetric encryption of a plain text payload using the provided key.
 * @param plainText The plain text bytes payload
 * @param key The secret key spec to use in the cipher.
 * @param iv The initialization vector to use in the cipher.
 * @param aad The {optional} additional associated data to use in the cipher.
 * @return The encrypted cipher text.
 */
fun aesEncrypt(plainText: ByteArray, key: SecretKeySpec, iv: ByteArray = randomIv(), aad: ByteArray? = null): ByteArray {
    val cipher = Cipher.getInstance(CRYPTO_ALGORITHM).apply {
        init(ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        aad?.let { updateAAD(it) }
    }

    val cipherText = cipher.doFinal(plainText)
    val byteBuffer = ByteBuffer.allocate(4 + iv.size + cipherText.size).apply {
        putInt(iv.size)
        put(iv)
        put(cipherText)
    }
    return byteBuffer.array()
}

/**
 * Symmetric encryption of a plain text payload using the provided key.
 * @param inputStream The plain text stream of bytes to encrypt.
 * @param key The secret key spec to use in the cipher.
 * @param iv The initialization vector to use in the cipher.
 * @param aad The {optional} additional associated data to use in the cipher.
 * @return The encrypted cipher stream.
 */
fun aesEncryptStream(inputStream: InputStream, key: SecretKeySpec, iv: ByteArray = randomIv(), aad: ByteArray? = null): InputStream {
    val cipher = Cipher.getInstance(CRYPTO_ALGORITHM).apply {
        init(ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        aad?.let { updateAAD(aad) }
    }

    val header = ByteBuffer.allocate(4 + iv.size).apply {
        putInt(iv.size)
        put(iv)
    }

    return HashingCipherInputStream(inputStream, cipher, MessageDigest.getInstance("SHA-512"), header.array())
}

/**
 * Symmetric deccryption of a cipher text payload using the provided key.
 * @param cipherText The cipher text bytes payload.
 * @param key The secret key spec to use in the cipher.
 * @param aad The {optional} additional associated data to use in the cipher.
 * @return The decrypted plain text bytes.
 */
fun aesDecrypt(cipherText: ByteArray, key: SecretKeySpec, aad: ByteArray? = null): ByteArray {
    if (cipherText.isEmpty()) {
        return cipherText
    }

    val byteBuffer = ByteBuffer.wrap(cipherText)
    val ivLength = byteBuffer.int
    require(ivLength in 12..16) { "invalid iv length" }

    val iv = ByteArray(ivLength)
    byteBuffer.get(iv)

    val payload = ByteArray(byteBuffer.remaining())
    byteBuffer.get(payload)

    val cipher = Cipher.getInstance(CRYPTO_ALGORITHM).apply {
        init(DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        aad?.let { updateAAD(it) }
    }
    return cipher.doFinal(payload)
}

/**
 * Symmetric deccryption of a cipher text payload using the provided key.
 * @param inputStream The cipher text bytes stream to decrypt.
 * @param key The secret key spec to use in the cipher.
 * @param aad The {optional} additional associated data to use in the cipher.
 * @return The decrypted plain text stream.
 */
fun aesDecryptStream(inputStream: InputStream, key: SecretKeySpec, aad: ByteArray? = null): InputStream {
    val ivLengthBytes = ByteArray(4)

    // Empty inputStream is already decrypted
    if (inputStream.read(ivLengthBytes) < 0) {
        return inputStream
    }

    val ivLength = ByteUtil.getUInt32(ivLengthBytes)
    require(ivLength in 12..16) { "invalid iv length" }

    val iv = ByteArray(ivLength.toInt())
    inputStream.read(iv)

    val cipher = Cipher.getInstance(CRYPTO_ALGORITHM).apply {
        init(DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        aad?.let { updateAAD(aad) }
    }
    return HashingCipherInputStream(inputStream, cipher, MessageDigest.getInstance(SHA_512))
}
