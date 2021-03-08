package io.provenance.p8e.encryption.experimental.ecies


import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator.generateKeyPair
import io.provenance.p8e.encryption.experimental.aes.aesDecrypt
import io.provenance.p8e.encryption.experimental.aes.aesEncrypt
import io.provenance.p8e.encryption.experimental.aes.emptyIv
import io.provenance.p8e.encryption.experimental.aes.randomIv
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val CRYPTO_ALGORITHM = "AES/GCM/NoPadding"
private const val MAC_LEN = 32
private const val KEY_LEN = 32
private const val HKDF_ALG = "ECDH"
private const val SECURITY_PROVIDER = "BC"

/**
 * Derive the HKDF secret of a shared key.
 */
private fun derive(secret: ByteArray, lenBytes: Int = 64): ByteArray {
    require(lenBytes % 8 == 0) { "lenBytes must be multiple of 8" }
    // Generate the
    val gen = HKDFBytesGenerator(SHA256Digest()).apply {
        init(HKDFParameters(secret, null, null))
    }

    val hkdf = ByteArray(lenBytes)
    val lenGen = gen.generateBytes(hkdf, 0, hkdf.size)
    require(lenGen == lenBytes) { "Failed to derive key. Expected $lenBytes bytes, generated $lenGen" }
    return hkdf
}

/**
 * Compute the shared key of an EC private / public key set.
 */
private fun computeSharedKey(privateKey: PrivateKey, publicKey: PublicKey): SecretKey {
    val keyAgreement = KeyAgreement.getInstance(HKDF_ALG, SECURITY_PROVIDER).apply {
        init(privateKey)
        doPhase(publicKey, true)
    }
    return SecretKeySpec(keyAgreement.generateSecret(), CRYPTO_ALGORITHM)
}

/**
 * Encrypt an ECIES payload with an ephemeral keypair intended for target.
 * @param targetPublicKey The target audience's public key.
 * @param payload The payload to encrypt.
 * @param iv The initialization vector to include into the cipher.
 * @param aad The {optional} additional associated data to include into the cipher.
 * @param ephemeralKeyPair The {optional} generated ephemeral keypair to encrypt the payload with.
 * @return The triple of (public key, crypto tag, and cipher text)
 */
fun eciesEncrypt(targetPublicKey: PublicKey, payload: ByteArray, iv: ByteArray = randomIv(), aad: ByteArray? = null, ephemeralKeyPair: KeyPair? = null): Triple<PublicKey, ByteArray, ByteArray> {

    // If the ephemeral key is not provided then generate one.
    // NOTE: this key must be on the same curve as the targetPublicKey.
    val ephemeral = ephemeralKeyPair ?: generateKeyPair(targetPublicKey as ECPublicKey)

    // HKDF shared secret derivation.
    val sharedKey = computeSharedKey(ephemeral.private, targetPublicKey)
    val sharedSecret = derive(sharedKey.encoded)

    // Split the secret into mac and key
    val keyBytes = sharedSecret.copyOf(KEY_LEN)
    val macBytes = sharedSecret.copyOfRange(KEY_LEN, KEY_LEN + MAC_LEN)

    // Encrypt the payload and tag for cryptogram.
    val key = SecretKeySpec(keyBytes, CRYPTO_ALGORITHM)
    val body = aesEncrypt(payload, key, iv, aad)
    val tag = aesEncrypt(macBytes, key, iv, aad)
    return Triple(ephemeral.public, tag, body)
}

/**
 * Utility for verification of the hmac tag in decryption.
 * @param tag The {optional} hmac tag of the hkdf derivation key for validation.
 * @param iv The initialization vector {default: zeros} to include into the cipher.
 */
class HmacVerification(val tag: ByteArray, val iv: ByteArray)

/**
 * Decrypt an ECIES encrypted payload with the private key.
 * @param privateKey The recipient's private key to decrypt with.
 * @param ephemeralPublicKey The ephemeral public key used to encrypt the payload.
 * @param payload The encrypted payload.
 * @param hmacVerification The {optional} iv and tag to validate the hkdf derivation.
 * @param aad The {optional} additional associated data to include into the cipher
 * @return The plain text decrypted payload.
 */
fun eciesDecrypt(privateKey: PrivateKey, ephemeralPublicKey: PublicKey, payload: ByteArray, aad: ByteArray? = null, hmacVerification: HmacVerification? = null): ByteArray {
    // HKDF shared secret derivation.
    val sharedKey = computeSharedKey(privateKey, ephemeralPublicKey)
    val sharedSecret = derive(sharedKey.encoded)

    // Split the secret into mac and key
    val keyBytes = sharedSecret.copyOf(KEY_LEN)
    val macBytes = sharedSecret.copyOfRange(KEY_LEN, KEY_LEN + MAC_LEN)

    // Encrypt the mac for payload verification.
    val key = SecretKeySpec(keyBytes, CRYPTO_ALGORITHM)

    // Validate the hmac tag against the mac if it's provided.
    hmacVerification?.let {
        val mac = aesEncrypt(macBytes, key, it.iv, aad)
        require(mac.contentEquals(it.tag)) { "Invalid MAC" }
    }

    // return decrypted payload.
    return aesDecrypt(payload, key, aad)
}
