package io.provenance.p8e.encryption.kdf

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.GeneralSecurityException



class ProvenanceHKDFSHA256 {

companion object {
    /**
     * Implements HKDF with SHA256 as hashing algorithm.
     * s
     * <ul>
     * <li><a href="https://en.wikipedia.org/wiki/Key_derivation_function">Key  derivation
     * function</a>  on Wikipedia. </li>
     * <li> <a href="https://tools.ietf.org/html/rfc5869">RFC 5869 HMAC-based Extract-and-Expand Key
     * Derivation Function (HKDF)</a> </li>
     * <li> <a href="http://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-132.pdf">NIST
     * */
    fun derive(secret: ByteArray?, info: ByteArray?, desiredKeyLengthInBits: Int): ByteArray? {
        if (secret == null) {
            return null
        }

        if (desiredKeyLengthInBits % 8 != 0) {
            throw ProvenanceHKDFCryptoException("DesiredKeyLengthInBits must be multiple of 8",IllegalArgumentException(
                    "desiredKeyLengthInBits must be multiple of 8 but is $desiredKeyLengthInBits"));
        }

        val desiredKeyLengthInBytes = desiredKeyLengthInBits / 8

        val derivationParameters = HKDFParameters(secret, null, info)

        val digest = SHA256Digest()
        val hkdfGenerator = HKDFBytesGenerator(digest)

        hkdfGenerator.init(derivationParameters)

        val hkdf = ByteArray(desiredKeyLengthInBytes)

        val generatedKeyLength = hkdfGenerator.generateBytes(hkdf, 0, hkdf.size)

        if (generatedKeyLength != desiredKeyLengthInBytes) {
            //wrap it.. time will tell if it's a good idea..
            throw ProvenanceHKDFCryptoException("Failed to derive key ..",
                            GeneralSecurityException(String.format("Failed to derive key. Expected %d bytes, generated %d ", desiredKeyLengthInBytes,
                            generatedKeyLength)))
        }
        return hkdf
    }
}

}
