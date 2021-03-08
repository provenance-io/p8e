package io.provenance.engine.crypto

import org.kethereum.crypto.CURVE
import org.kethereum.crypto.api.ec.ECDSASignature
import java.math.BigInteger

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
