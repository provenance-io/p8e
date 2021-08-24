package io.provenance.engine.crypto

import io.p8e.util.toHexString
import io.provenance.p8e.shared.extension.logger
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1StreamParser
import org.kethereum.crypto.CURVE
import org.kethereum.crypto.api.ec.ECDSASignature
import org.kethereum.crypto.impl.ec.canonicalise
import java.lang.Exception
import java.lang.IllegalStateException
import java.math.BigInteger
import kotlin.experimental.and

// A Zero value byte
const val ZERO = 0x0.toByte()
// The (Order / 2) (used for signature malleability checks -- to discard the negative signature point)
private val HALF_CURVE_ORDER = CURVE.n.shiftRight(1)

/**
 * encodeAsBTC returns the ECDSA signature as a ByteArray of r || s,
 * where both r and s are encoded into 32 byte big endian integers.
 */
fun ECDSASignature.encodeAsBTC(): ByteArray {
    // Canonicalize - In order to remove malleability,
    // we set s = curve_order - s, if s is greater than curve.Order() / 2.
    var sigS = this.s
    if (sigS > HALF_CURVE_ORDER) {
        sigS = CURVE.n.subtract(sigS)
    }

    val sBytes = sigS.getUnsignedBytes()
    val rBytes = this.r.getUnsignedBytes()

    require(rBytes.size <= 32) { "cannot encode r into BTC Format, size overflow (${rBytes.size} > 32)" }
    require(sBytes.size <= 32) { "cannot encode s into BTC Format, size overflow (${sBytes.size} > 32)" }

    val signature = ByteArray(64)
    // 0 pad the byte arrays from the left if they aren't big enough.
    System.arraycopy(rBytes, 0, signature, 32 - rBytes.size, rBytes.size)
    System.arraycopy(sBytes, 0, signature, 64 - sBytes.size, sBytes.size)

    return signature
}

/**
 * Returns the bytes from a BigInteger as an unsigned version by truncating a byte if needed.
 */
fun BigInteger.getUnsignedBytes(): ByteArray {
    val bytes = this.toByteArray();

    if (bytes[0] == ZERO)
    {
        return bytes.drop(1).toByteArray()
    }

    return bytes;
}

fun ByteArray.extractRAndS(): Pair<BigInteger, BigInteger> {
    val startR = if (this[1] and 0x80.toByte() != 0.toByte()) 3 else 2
    val lengthR = this[startR + 1].toInt()
    val startS = startR + 2 + lengthR
    val lengthS = this[startS + 1].toInt()

    return BigInteger(this, startR + 2, lengthR) to BigInteger(this, startS + 2, lengthS)
}

fun ByteArray.toECDSASignature(canonical: Boolean) = extractRAndS().let { (r, s) ->
    ECDSASignature(r, s)
}.let {
    if (canonical) {
        it.canonicalise()
    } else {
        it
    }
}
