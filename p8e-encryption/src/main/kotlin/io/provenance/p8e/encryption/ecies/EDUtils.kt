package io.provenance.p8e.encryption.ecies

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jcajce.provider.asymmetric.edec.BCEdDSAPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.edec.BCEdDSAPublicKey
import org.bouncycastle.jcajce.provider.asymmetric.edec.BCXDHPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.edec.BCXDHPublicKey
import org.bouncycastle.jcajce.provider.digest.SHA512.Digest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayInputStream
import java.lang.reflect.Constructor
import java.math.BigInteger
import java.security.*
import kotlin.experimental.and

private object EDReflectUtils {
    val toPublicEd25519Ctor: Constructor<BCEdDSAPublicKey>
    val toPrivateEd25519Ctor: Constructor<BCEdDSAPrivateKey>
    val toPublicX25519Ctor: Constructor<BCXDHPublicKey>
    val toPrivateX25519Ctor: Constructor<BCXDHPrivateKey>

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
            Security.addProvider(BouncyCastleProvider())
    }

    init {
        val findConstructor =
           fun(ctors: Array<Constructor<*>>, name: String) =
                ctors.filter {
                    it.parameterTypes[0].name == name
                }.singleOrNull() {
                    it.setAccessible(true)
                    true
                }.let {
                    it as Constructor<*>
                }

        toPublicEd25519Ctor = BCEdDSAPublicKey::class.java.let {
            findConstructor(it.declaredConstructors, "org.bouncycastle.asn1.x509.SubjectPublicKeyInfo") as Constructor<BCEdDSAPublicKey>
        }

        toPrivateEd25519Ctor = BCEdDSAPrivateKey::class.java.let {
            findConstructor(it.declaredConstructors, "org.bouncycastle.asn1.pkcs.PrivateKeyInfo") as Constructor<BCEdDSAPrivateKey>
        }

        // Cache the constructors upfront to avoid time spent searching for them each time the utils object is used.
        toPublicX25519Ctor = BCXDHPublicKey::class.java.let {
            findConstructor(it.declaredConstructors, "org.bouncycastle.asn1.x509.SubjectPublicKeyInfo") as Constructor<BCXDHPublicKey>
        }

        toPrivateX25519Ctor = BCXDHPrivateKey::class.java.let {
            findConstructor(it.declaredConstructors, "org.bouncycastle.asn1.pkcs.PrivateKeyInfo") as Constructor<BCXDHPrivateKey>
        }
    }
}

object EDUtils {
    private val curve25519P_weierstrass = BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949", 10)

    val PREFERRED_CURVE = "Ed25519"
    val KNOWN_CURVES = arrayOf("Ed25519")

    fun generateKeyPair(): KeyPair {
        val privKeyBytes = ByteArray(32).apply {
            SecureRandom().nextBytes(this)
        }

        val pubKeyBytes = ByteArray(32).apply {
            Ed25519.generatePublicKey(privKeyBytes, 0, this, 0)
        }

        return KeyPair(pubKeyBytes.toPublicKey(), privKeyBytes.toPrivateKey())
    }

    /**
     * Convert KeyPair to a HEX String representation.
     * Key format is {bytes[32]private_key}{bytes[32]public_key}
     */
    fun toHex(keypair: KeyPair): String {
        val publicBytes = keypair.public.let(::toByteArray)
        val privateBytes = keypair.private.let(::toByteArray)

        return (privateBytes + publicBytes).let {
            String(Hex.encode(it)).toUpperCase()
        }
    }

    /**
     * Convert from a 64 Byte HEX String to a KeyPair.
     * Key format is {bytes[32]private_key}{bytes[32]public_key}
     */
    fun fromHex(hex: String) =
        Hex.decode(hex).let {
            KeyPair(it.sliceArray(32..63).toPublicKey(),
                it.sliceArray(0..31).toPrivateKey())
        }

    fun toByteArray(publicKey: PublicKey) =
        ASN1InputStream(ByteArrayInputStream(publicKey.encoded)).let {
            (it.readObject() as ASN1Sequence).getObjectAt(1)
        }.let {
            it.toASN1Primitive().encoded.sliceArray(3..34)
        }

    fun toByteArray(privateKey: PrivateKey) =
        ASN1InputStream(ByteArrayInputStream(privateKey.encoded)).let {
            (it.readObject() as ASN1Sequence).getObjectAt(2)
        }.let {
            it.toASN1Primitive().encoded.sliceArray(4..35)
        }

    fun ByteArray.toPublicKey(): PublicKey =
        EDReflectUtils.toPublicEd25519Ctor.newInstance(
            SubjectPublicKeyInfo(AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), this))

    fun ByteArray.toPrivateKey(): PrivateKey =
        EDReflectUtils.toPrivateEd25519Ctor.newInstance(
            PrivateKeyInfo(AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), DEROctetString(this)))

    fun toX25519(keypair: KeyPair) = KeyPair(toX25519(keypair.public), toX25519(keypair.private))

    fun toX25519(key: PublicKey) = key.let {
        X25519Utils.toPublicKey(toByteArray(it).toPublicX25519())
    }

    fun toX25519(key: PrivateKey) = key.let {
        X25519Utils.toPrivateKey(toByteArray(it).toPrivateX25519())
    }

    /**
     * https://github.com/FiloSottile/age <-- See this repo for info on the conversion from the ED25519 curve to X25519
     * for use with XDH.
     */
    private fun ByteArray.toPublicX25519(): ByteArray {
        val bigEndianY = this.toBigEndian()
        bigEndianY[0] = bigEndianY[0].and(0x7F)

        val y = BigInteger(bigEndianY)
        val denom = BigInteger.ONE
                .subtract(y)
                .modInverse(curve25519P_weierstrass)

        val u = BigInteger.ONE
                .add(y)
                .multiply(denom)
                .mod(curve25519P_weierstrass)

        return u.toByteArray().toBigEndian()
    }

    private fun ByteArray.toPrivateX25519() = Digest.getInstance("SHA512").digest(this).sliceArray(0..31)

    private fun ByteArray.toBigEndian(size: Int = 32) =
            this.let {
                val bytes = ByteArray(size)
                for (i in 0..it.size - 1) {
                    bytes[it.size - 1 - i] = it[i]
                }

                bytes
            }
}

private object X25519Utils {
    fun toPublicKey(keyBytes: ByteArray): PublicKey =
            EDReflectUtils.toPublicX25519Ctor.newInstance(
                    SubjectPublicKeyInfo(AlgorithmIdentifier(EdECObjectIdentifiers.id_X25519), keyBytes))

    fun toPrivateKey(keyBytes: ByteArray): PrivateKey =
            EDReflectUtils.toPrivateX25519Ctor.newInstance(
                    PrivateKeyInfo(AlgorithmIdentifier(EdECObjectIdentifiers.id_X25519), DEROctetString(keyBytes)))
}
