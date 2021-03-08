package io.provenance.p8e.encryption.ecies

import java.security.PublicKey
import java.util.Arrays

data class ProvenanceECIESCryptogram(val ephemeralPublicKey: PublicKey,
                                     val tag: ByteArray,
                                     val encryptedData: ByteArray) {
    //why not free read here https://stackoverflow.com/questions/37524422/equals-method-for-data-class-in-kotlin
    //thanks intellij
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProvenanceECIESCryptogram

        if (ephemeralPublicKey != other.ephemeralPublicKey) return false
        if (!Arrays.equals(tag, other.tag)) return false
        if (!Arrays.equals(encryptedData, other.encryptedData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ephemeralPublicKey.hashCode()
        result = 31 * result + Arrays.hashCode(tag)
        result = 31 * result + Arrays.hashCode(encryptedData)
        return result
    }
}
