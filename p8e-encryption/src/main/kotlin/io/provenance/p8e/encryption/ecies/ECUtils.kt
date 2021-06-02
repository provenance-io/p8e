package io.provenance.p8e.encryption.ecies

import com.google.common.io.BaseEncoding
import io.provenance.p8e.encryption.util.ByteUtil
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.*
import java.security.spec.InvalidKeySpecException
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import javax.crypto.SecretKey

object ECUtils {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // Legacy DIME encryption curve.  P-256 curve is the one to use going forward.
    val LEGACY_DIME_CURVE = "secp256k1"

    // Industry standard EC curve with widest platform support (NIST P-256)
    val STANDARD_CURVE = "P-256"

    // A listing of all supported curves for encryption/decryption.
    val KNOWN_CURVES = arrayOf("secp128r1", "secp160k1", "secp160r1", "secp160r2", "secp192k1",
        "secp192r1", "secp224k1", "secp224r1", "secp256k1", "secp256r1", "secp384r1", "secp521r1", "sect113r1",
        "sect113r2", "sect131r1", "sect131r2", "sect163k1", "sect163r1", "sect163r2", "sect193r1", "sect193r2",
        "sect233k1", "sect233r1", "sect239k1", "sect283k1", "sect283r1", "sect409k1", "sect409r1", "sect571k1",
        "sect571r1", "sm2p256v1", "B-163", "B-233", "B-283", "B-409", "B-571", "K-163", "K-233", "K-283", "K-409",
        "K-571", "P-192", "P-224", "P-256", "P-384", "P-521")

    // A list of recommended curves
    val RECOMMENDED_CURVES = arrayOf(STANDARD_CURVE, "secp256r1", LEGACY_DIME_CURVE)

    //standardized private key size for Provenance (Node js `elliptic` does not like keys not being multiple of 32.
    val PRIVATE_KEY_SIZE = 32
    val PUBLIC_KEY_SIZE = 64
    val KDF_SIZE = 512
    val AGREEKEY_SIZE = 256
    val provider = "BC"

    /**
     * Walk through the known curves matching parameters to determine the "name" of the curve used for the given key.
     */
    fun curveName(publicKey: ECPublicKey): String {
        ECNamedCurveTable.getNames().toList().map { curve ->
            ECNamedCurveTable.getParameterSpec(curve as String).also { spec ->
                if (publicKey.parameters.g.equals(spec.g) &&
                        publicKey.parameters.h == spec.h &&
                        publicKey.parameters.n == spec.n) {
                    return curve
                }
            }
        }
        throw InvalidKeySpecException("Curve of given public key could not be determined.")
    }
    fun PublicKey?.curveName() = curveName(this as ECPublicKey)

    /**
     * Converts an EC public key to a byte array by encoding Q point parameter.
     *
     * @param publicKey An EC public key to be converted.
     * @return A byte array representation of the EC public key.
     */
    fun convertPublicKeyToBytes(publicKey: PublicKey): ByteArray {
        return (publicKey as ECPublicKey).q.getEncoded(false)
    }

    /**
     * Converts a shared secret key (usually used for AES based operations) to a
     * byte array.
     *
     * @param sharedSecretKey A shared key to be converted to bytes.
     * @return A byte array representation of the shared secret key.
     */
    fun convertSharedSecretKeyToBytes(sharedSecretKey: SecretKey): ByteArray {
        return sharedSecretKey.encoded
    }

    /**
     * Converts an EC private key to bytes by encoding the D number parameter.
     *
     * @param privateKey An EC private key to be converted to bytes.
     * @return A byte array containing the representation of the EC private key.
     */
    fun convertPrivateKeyToBytes(privateKey: PrivateKey): ByteArray {
        return (privateKey as ECPrivateKey).d.toByteArray()
    }

    /**
     * TO be used when passing it to external system name node js chaincode for now..probably should be used for all
     * other places too
     */
    fun convertPrivateKeyToBytesPadded(privateKey: PrivateKey): ByteArray {
        return toBytesPadded((privateKey as ECPrivateKey).d, PRIVATE_KEY_SIZE)
    }

    fun convertPrivateKeyJSEToBytesPadded(privateKey: PrivateKey): ByteArray {
        return toBytesPadded((privateKey as java.security.interfaces.ECPrivateKey).s, PRIVATE_KEY_SIZE)
    }

    /**
     * Convert a byte array to an EC private key by decoding the D number
     * parameter.
     *
     * @param keyBytes Bytes to be converted to the EC private key.
     * @return An instance of EC private key decoded from the input bytes.
     * @throws InvalidKeySpecException The provided key bytes are not a valid EC
     * private key.
     */
    @Throws(InvalidKeySpecException::class)
    fun convertBytesToPrivateKey(keyBytes: ByteArray, curve: String = LEGACY_DIME_CURVE): PrivateKey {
        try {

            val kf = KeyFactory.getInstance("ECDH", provider)
            val keyInteger = ByteUtil.unsignedBytesToBigInt(keyBytes)
            val ecSpec = ECNamedCurveTable.getParameterSpec(curve)
                    ?: throw InvalidKeySpecException("Could not get parameter spec for '${curve}': ensure crypto provider is correctly inialized")
            val keySpec = ECPrivateKeySpec(keyInteger, ecSpec)

            return kf.generatePrivate(keySpec)
        } catch (ex: Exception) {
            throw InvalidKeySpecException(ex.message, ex)
        }
    }

    fun convertBytesToKeyspec(pub: ByteArray, priv: ByteArray, curve: String = LEGACY_DIME_CURVE) = KeyPair(
            convertBytesToPublicKey(pub, curve),
            convertBytesToPrivateKey(priv, curve)
    )

    @Throws(InvalidKeySpecException::class)
    fun convertBytesToPublicKey(keyBytes: ByteArray, curve: String = LEGACY_DIME_CURVE): PublicKey {
        try {
            val kf = KeyFactory.getInstance("ECDH", provider)

            val ecSpec = ECNamedCurveTable.getParameterSpec(curve)
                    ?: throw InvalidKeySpecException("Could not get parameter spec for '${curve}': ensure crypto provider is correctly inialized")
            val point = ecSpec.curve.decodePoint(keyBytes)
            val pubSpec = ECPublicKeySpec(point, ecSpec)

            return kf.generatePublic(pubSpec)
        } catch (ex: Exception) {
            throw InvalidKeySpecException(ex.message, ex)
        }
    }


    fun toBytesPadded(value: BigInteger, length: Int): ByteArray {
        val result = ByteArray(length)
        val bytes = value.toByteArray()

        val bytesLength: Int
        val srcOffset: Int
        if (bytes[0].toInt() == 0) {
            bytesLength = bytes.size - 1
            srcOffset = 1
        } else {
            bytesLength = bytes.size
            srcOffset = 0
        }

        if (bytesLength > length) {
            throw RuntimeException("Input is too large to put in byte array of size $length")
        }

        val destOffset = length - bytesLength
        System.arraycopy(bytes, srcOffset, result, destOffset, bytesLength)
        return result
    }

    /**
     * Get private key from a base64 encoded padded private key.
     */
    fun privateKeyFromPaddedByteArray(base64EncodedPaddedPrivateKey: String): PrivateKey {
        val privateKeyInteger = ByteUtil.unsignedBytesToBigInt(BaseEncoding.base64().decode(base64EncodedPaddedPrivateKey))
        return convertBytesToPrivateKey(privateKeyInteger.toByteArray())
    }

    /**
     * Padded private key converted to BigInteger.Reverse of what is done in method convertPrivateKeyToBytesPadded()
     * @param value Padded Byte Array.
     * @param offset Offset if any, most cases 0
     * @param length Length of the private Key
     */
    fun toBigInt(value: ByteArray, offset: Int, length: Int): BigInteger {
        return ByteUtil.unsignedBytesToBigInt(Arrays.copyOfRange(value, offset, offset + length))
    }

    fun toBigInt(value: ByteArray): BigInteger {
        return ByteUtil.unsignedBytesToBigInt(value)
    }

    /**
     * Decode base 64 encode strings
     * @param base64EncodedString base64 encoded string.
     */
    fun decodeString(base64EncodedString: String): ByteArray {
        return BaseEncoding.base64().decode(base64EncodedString)
    }

    fun getProvenanceCryptogram(base64EncodedPubKey: String, base64EncodedTag: String, base64EncodedEncryptedData: String, curve: String = LEGACY_DIME_CURVE): ProvenanceECIESCryptogram {
        val publicKey: PublicKey = ECUtils.convertBytesToPublicKey(ECUtils.decodeString(base64EncodedPubKey), curve)

        return ProvenanceECIESCryptogram(publicKey,
                ECUtils.decodeString(base64EncodedTag),
                ECUtils.decodeString(base64EncodedEncryptedData))

    }

    fun publicKeyToPem(publicKey: PublicKey): String {
        val pemStrWriter = StringWriter()
        val pemWriter = JcaPEMWriter(pemStrWriter)
        pemWriter.writeObject(publicKey)
        pemWriter.close()
        return pemStrWriter.toString()
    }

    fun privateKeyToPem(privateKey: PrivateKey): String {
        val pemStrWriter = StringWriter()
        val pemWriter = JcaPEMWriter(pemStrWriter)
        pemWriter.writeObject(privateKey)
        pemWriter.close()
        return pemStrWriter.toString()
    }

    fun publicKeyEncoded(publicKey: PublicKey): String {
        return Base64.getEncoder().encode(ECUtils.convertPublicKeyToBytes(publicKey)).toString(Charsets.UTF_8)
    }

    fun validateKeyPair(keyPair: KeyPair): Boolean {
        // create a challenge
        val challenge = ByteArray(10000)
        ThreadLocalRandom.current().nextBytes(challenge)

        // sign using the private key
        val sig = Signature.getInstance("ECDSA")
        sig.initSign(keyPair.private)
        sig.update(challenge)
        val signature = sig.sign()

        // verify signature using the public key
        sig.initVerify(keyPair.public)
        sig.update(challenge)

        return sig.verify(signature)
    }

    fun toPublicKey(privateKey: PrivateKey,curve: String = LEGACY_DIME_CURVE): PublicKey? {
        val kf = KeyFactory.getInstance("ECDH", provider)

        val ecSpec = ECNamedCurveTable.getParameterSpec(curve)
                ?: throw InvalidKeySpecException("Could not get parameter spec for '${curve}': ensure crypto provider is correctly inialized")

        val Q: ECPoint =
                ecSpec.g.multiply((privateKey as ECPrivateKey).d)

        val pubSpec = ECPublicKeySpec(Q, ecSpec)
        return kf.generatePublic(pubSpec)
    }
}
