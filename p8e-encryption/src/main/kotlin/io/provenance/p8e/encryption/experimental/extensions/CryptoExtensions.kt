package io.provenance.p8e.encryption.experimental.extensions

import com.fortanix.sdkms.v1.api.SecurityObjectsApi
import com.fortanix.sdkms.v1.model.AgreeKeyMechanism
import com.fortanix.sdkms.v1.model.AgreeKeyRequest
import com.fortanix.sdkms.v1.model.EllipticCurve
import com.fortanix.sdkms.v1.model.KeyObject
import com.fortanix.sdkms.v1.model.ObjectType
import com.fortanix.sdkms.v1.model.SobjectDescriptor
import com.fortanix.sdkms.v1.model.SobjectRequest
import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.encryption.ecies.ProvenanceECIESCryptogram
import io.provenance.p8e.encryption.experimental.aes.aesDecrypt
import io.provenance.p8e.encryption.experimental.aes.aesEncrypt
import io.provenance.p8e.encryption.experimental.aes.emptyIv
import io.provenance.p8e.encryption.experimental.aes.randomIv
import io.provenance.p8e.encryption.experimental.ecies.HmacVerification
import io.provenance.p8e.encryption.experimental.ecies.eciesDecrypt
import io.provenance.p8e.encryption.experimental.ecies.eciesEncrypt
import io.provenance.proto.encryption.EncryptionProtos
import java.security.PrivateKey
import java.security.PublicKey
import java.util.*
import javax.crypto.spec.SecretKeySpec

private fun <T, U, V, R> Triple<T, U, V>.map(fn: (T, U, V) -> R): R = fn(first, second, third)

/**
 * Properly decrypt a provenance cryptogram into a byte array.
 * @param privateKey The target's private key for decryption.
 *
 * @param aad The {optional} additional associated data for the cipher input.
 * @return The decrypted payload bytes.
 */
fun ProvenanceECIESCryptogram.eciesDecrypt(privateKey: PrivateKey, iv: ByteArray = emptyIv(), aad: ByteArray? = null): ByteArray =
        eciesDecrypt(privateKey, ephemeralPublicKey, encryptedData, aad, HmacVerification(tag, iv))

/**
 * Properly encrypt a bytes payload into a provenance cryptogram.
 * @param targetPublicKey The target's public key for cryptogram receipt.
 * @param aad The {optional} additional associated data for the cipher input.
 * @return The encrypted provenance cryptogram.
 */
fun ByteArray.eciesEncrypt(targetPublicKey: PublicKey, iv: ByteArray = emptyIv(), aad: ByteArray? = null): ProvenanceECIESCryptogram =
        eciesEncrypt(targetPublicKey, this, iv, aad).map(::ProvenanceECIESCryptogram)

/**
 *
 */
fun ByteArray.aesDecrypt(key: SecretKeySpec, aad: ByteArray? = null) = aesDecrypt(this, key, aad)

/**
 *
 */
fun ByteArray.aesEncrypt(key: SecretKeySpec, iv: ByteArray = randomIv(), aad: ByteArray? = null) = aesEncrypt(this, key, iv, aad)

/**
 *
 */
fun ByteArray.toPublicKey() = ECUtils.convertBytesToPublicKey(this)

/**
 *
 */
fun ByteArray.toPrivateKey() = ECUtils.convertBytesToPrivateKey(this)

/**
 *
 */
fun ByteArray.toSecretKeySpec() = ProvenanceAESCrypt.secretKeySpecGenerate(this)

/**
 *
 */
fun EncryptionProtos.Audience.toCryptogram(): ProvenanceECIESCryptogram {
    return ProvenanceECIESCryptogram(
            Base64.getDecoder().decode(ephemeralPubkey.toStringUtf8()).toPublicKey(),
            Base64.getDecoder().decode(tag.toStringUtf8()),
            Base64.getDecoder().decode(encryptedDek.toStringUtf8())
    )
}

fun String.toSecretKeySpecProv() = ProvenanceAESCrypt.secretKeySpecGenerate(Base64.getDecoder().decode(this))

fun String.toAgreeKey(transientKey: String, securityObjectsApi: SecurityObjectsApi): KeyObject {
    val keyUuid = this
    val request = AgreeKeyRequest().apply {
        privateKey = SobjectDescriptor().kid(keyUuid)
        publicKey = SobjectDescriptor().transientKey(transientKey)
        name = keyUuid
        keySize = ECUtils.AGREEKEY_SIZE
        keyType = ObjectType.OPAQUE
        mechanism = AgreeKeyMechanism.HELLMAN
        transient = true
    }
    return securityObjectsApi.agreeKey(request)
}

/**
 * A transient security object will be created, meaning this security object is for one time use
 * and will not be stored into SmartKey's repository.
 */
fun PublicKey.toTransientSecurityObject(securityObjectsApi: SecurityObjectsApi): KeyObject {
    val ephemeralPublicKey = this
    val request = SobjectRequest().apply {
        value = ephemeralPublicKey.encoded
        ellipticCurve = EllipticCurve.SECP256K1
        objType = ObjectType.EC
        transient = true
    }
    return securityObjectsApi.importSecurityObject(request)
}
