package io.provenance.os.util

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.util.io.pem.PemReader
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.io.StringWriter
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.spec.KeySpec
import java.security.spec.PKCS8EncodedKeySpec

object CertificateUtil {
    private const val CERT_TYPE = "X.509"
    private const val KEY_TYPE = "ECDSA"

    fun ecdsaPemToPrivateKey(privateKeyPem: String): PrivateKey {
        val kf = KeyFactory.getInstance(KEY_TYPE, BouncyCastleProvider.PROVIDER_NAME)
        val keySpec = toKeySpec(privateKeyPem)
        return kf.generatePrivate(keySpec)
    }

    fun x509PemToPublicKey(publicKeyPem: String): PublicKey {
        val cf = CertificateFactory.getInstance(CERT_TYPE, BouncyCastleProvider.PROVIDER_NAME)
        return cf.generateCertificate(ByteArrayInputStream(publicKeyPem.toByteArray(Charsets.UTF_8))).publicKey
    }

    fun pemToPublicKey(pem: String): PublicKey {
        val keyPemReader = StringReader(pem)
        lateinit var pemPair: Any
        PEMParser(keyPemReader).use { pemParser -> pemPair = pemParser.readObject() }

        if (pemPair is SubjectPublicKeyInfo) {
            return JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getPublicKey(pemPair as SubjectPublicKeyInfo)
        } else if (pemPair is PEMKeyPair) {
            return JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getPublicKey((pemPair as PEMKeyPair).publicKeyInfo)

        }
        throw IllegalArgumentException("Invalid key PEM - must be PrivateKeyInfo or PEMKeyPair: $pem")
    }

    fun publicKeyToPem(publicKey: PublicKey): String {
        return keyToPem(publicKey)
    }

    fun privateKeyToPem(privateKey: PrivateKey): String {
        return keyToPem(privateKey)
    }

    private fun <T> keyToPem(key: T): String {
        val writer = StringWriter()
        JcaPEMWriter(writer)
            .use {
                it.writeObject(key)
            }
        return writer.toString()
    }

    @Throws(CryptoException::class)
    private fun toKeySpec(key: String): KeySpec {
        try {
            val obj = PemReader(StringReader(key)).readPemObject()

            return PKCS8EncodedKeySpec(obj.content)
        } catch (e: Exception) {
            throw CryptoException("Failed to convert private key bytes", e)
        }
    }

    init {
        Security.addProvider(BouncyCastleProvider())
    }
}
