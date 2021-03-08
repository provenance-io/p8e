package io.p8e.crypto

/*
DIRECT COPY
 * Copyright 2019 Web3 Labs Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */


import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jcajce.provider.digest.Keccak
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException


/** Cryptographic hash functions.  */
object Hash {
    /**
     * Generates a digest for the given `input`.
     *
     * @param input The input to digest
     * @param algorithm The hash algorithm to use
     * @return The hash value for the given input
     * @throws RuntimeException If we couldn't find any provider for the given algorithm
     */
    fun hash(input: ByteArray?, algorithm: String): ByteArray {
        return try {
            val digest = MessageDigest.getInstance(algorithm.toUpperCase())
            digest.digest(input)
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("Couldn't find a $algorithm provider", e)
        }
    }

    /**
     * Keccak-256 hash function.
     *
     * @param hexInput hex encoded input data with optional 0x prefix
     * @return hash value as hex encoded string
     */
    fun sha3(hexInput: String): String {
        val bytes: ByteArray = Numeric.hexStringToByteArray(hexInput)
        val result = sha3(bytes)
        return Numeric.toHexString(result)
    }
    /**
     * Keccak-256 hash function.
     *
     * @param input binary encoded input data
     * @param offset of start of data
     * @param length of data
     * @return hash value
     */
    /**
     * Keccak-256 hash function.
     *
     * @param input binary encoded input data
     * @return hash value
     */
    @JvmOverloads
    fun sha3(input: ByteArray, offset: Int = 0, length: Int = input.size): ByteArray {
        val kecc: Keccak.DigestKeccak =
            Keccak.Digest256()
        kecc.update(input, offset, length)
        return kecc.digest()
    }

    /**
     * Keccak-256 hash function that operates on a UTF-8 encoded String.
     *
     * @param utf8String UTF-8 encoded string
     * @return hash value as hex encoded string
     */
    fun sha3String(utf8String: String): String {
        return Numeric.toHexString(sha3(utf8String.toByteArray(StandardCharsets.UTF_8)))
    }

    /**
     * Generates SHA-256 digest for the given `input`.
     *
     * @param input The input to digest
     * @return The hash value for the given input
     * @throws RuntimeException If we couldn't find any SHA-256 provider
     */
    fun sha256(input: ByteArray?): ByteArray {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(input)
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("Couldn't find a SHA-256 provider", e)
        }
    }

    fun hmacSha512(key: ByteArray?, input: ByteArray): ByteArray {
        val hMac =
            HMac(SHA512Digest())
        hMac.init(KeyParameter(key))
        hMac.update(input, 0, input.size)
        val out = ByteArray(64)
        hMac.doFinal(out, 0)
        return out
    }

    fun sha256hash160(input: ByteArray?): ByteArray {
        val sha256 = sha256(input)
        val digest = RIPEMD160Digest()
        digest.update(sha256, 0, sha256.size)
        val out = ByteArray(20)
        digest.doFinal(out, 0)
        return out
    }
}
