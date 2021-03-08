package io.provenance.engine.crypto

// Copyright (c) 2020 Figure Technologies Inc.
// The contents of this file were derived from an implementation
// by the btcsuite developers https://github.com/btcsuite/btcutil.

// Copyright (c) 2017 The btcsuite developers
// Use of this source code is governed by an ISC
// license that can be found in the LICENSE file.

import com.google.common.io.BaseEncoding

infix fun Int.min(b: Int): Int = b.takeIf { this > b } ?: this
infix fun UByte.shl(bitCount: Int) = ((this.toInt() shl bitCount) and 0xff).toUByte()
infix fun UByte.shr(bitCount: Int) = (this.toInt() shr bitCount).toUByte()

/**
 * Given an array of bytes, associate an HRP and return a Bech32Data instance.
 */
fun ByteArray.toBech32Data(hrp: String = Bech32.PROVENANCE_MAINNET_ACCOUNT_PREFIX) =
    Bech32Data(hrp, Bech32.convertBits(this, 8, 5, true))

/**
 * Using a string in bech32 encoded address format, parses out and returns a Bech32Data instance
 */
fun String.toBech32Data() = Bech32.decode(this)

/**
 * Bech32 Data encoding instance containing data for encoding as well as a human readable prefix
 */
data class Bech32Data(val hrp: String, val fiveBitData: ByteArray) {

    /**
     * The encapsulated data as typical 8bit bytes.
     */
    val data = Bech32.convertBits(fiveBitData, 5, 8, false)

    /**
     * The encapsulated data returned as a Hexadecimal string
     */
    val hexData = BaseEncoding.base16().encode(this.data)

    /**
     * Address is the Bech32 encoded value of the data prefixed with the human readable portion and
     * protected by an appended checksum.
     */
    val address = Bech32.encode(hrp, fiveBitData)

    /**
     * Checksum for encapsulated data + hrp
     */
    val checksum = Bech32.checksum(this.hrp, this.fiveBitData.toTypedArray())

    /**
     * The Bech32 Address toString prints state information for debugging purposes.
     * @see address() for the bech32 encoded address string output.
     */
    override fun toString(): String {
        return "bech32 : ${this.address}\nhuman: ${this.hrp} \nbytes: ${this.hexData}"
        /*
        bech32 : provenance1gx58vp8pryh3jkvxnkvzmd0hqmqqnyqxrtvheq
        human: provenance
        bytes: 41A87604E1192F1959869D982DB5F706C0099006
         */
    }
}

/**
 * BIP173 compliant processing functions for handling Bech32 encoding for addresses
 */
class Bech32 {

    companion object {
        const val CHECKSUM_SIZE = 6
        const val MIN_VALID_LENGTH = 8
        const val MAX_VALID_LENGTH = 90
        const val MIN_VALID_CODEPOINT = 33
        const val MAX_VALID_CODEPOINT = 126

        const val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

        const val PROVENANCE_MAINNET_PREFIX                             = "pb"
        const val PROVENANCE_MAINNET_ACCOUNT_PREFIX           = PROVENANCE_MAINNET_PREFIX
        const val PROVENANCE_MAINNET_PUBKEY_PREFIX            = PROVENANCE_MAINNET_PREFIX + "pub"
        const val PROVENANCE_MAINNET_VALIDATOR_ACCOUNT_PREFIX = PROVENANCE_MAINNET_PREFIX + "valopr"
        const val PROVENANCE_MAINNET_VALIDATOR_PUBKEY_PREFIX  = PROVENANCE_MAINNET_PREFIX + "valoprpub"
        const val PROVENANCE_MAINNET_CONSENSUS_ACCOUNT_PREFIX = PROVENANCE_MAINNET_PREFIX + "valcons"
        const val PROVENANCE_MAINNET_CONSENSUS_PUBKEY_PREFIX  = PROVENANCE_MAINNET_PREFIX + "valconspub"

        // Test net account prefixes are broken out seperately so keys/accounts used for test can be easily identified

        const val PROVENANCE_TESTNET_PREFIX                             = "tp"
        const val PROVENANCE_TESTNET_ACCOUNT_PREFIX           = PROVENANCE_TESTNET_PREFIX
        const val PROVENANCE_TESTNET_PUBKEY_PREFIX            = PROVENANCE_TESTNET_PREFIX + "pub"
        const val PROVENANCE_TESTNET_VALIDATOR_ACCOUNT_PREFIX = PROVENANCE_TESTNET_PREFIX + "valopr"
        const val PROVENANCE_TESTNET_VALIDATOR_PUBKEY_PREFIX  = PROVENANCE_TESTNET_PREFIX + "valoprpub"
        const val PROVENANCE_TESTNET_CONSENSUS_ACCOUNT_PREFIX = PROVENANCE_TESTNET_PREFIX + "valcons"
        const val PROVENANCE_TESTNET_CONSENSUS_PUBKEY_PREFIX  = PROVENANCE_TESTNET_PREFIX + "valconspub"


        /**
         * Decodes a Bech32 String
         */
        fun decode(bech32: String): Bech32Data {
            require(bech32.length >= MIN_VALID_LENGTH && bech32.length <= MAX_VALID_LENGTH) { "invalid bech32 string length" }
            require(bech32.toCharArray().none { c -> c.toInt() < MIN_VALID_CODEPOINT || c.toInt() > MAX_VALID_CODEPOINT })
            { "invalid character in bech32: ${bech32.toCharArray().map { c -> c.toInt() }
                .filter { c -> c.toInt() < MIN_VALID_CODEPOINT || c.toInt() > MAX_VALID_CODEPOINT }}" }

            require(bech32.equals(bech32.toLowerCase()) || bech32.equals(bech32.toUpperCase()))
            { "bech32 must be either all upper or lower case" }
            require(bech32.substring(1).dropLast(CHECKSUM_SIZE).contains('1')) { "invalid index of '1'" }

            val hrp = bech32.substringBeforeLast('1').toLowerCase()
            val dataString = bech32.substringAfterLast('1').toLowerCase()

            require(dataString.toCharArray().all { c -> charset.contains(c) }) { "invalid data encoding character in bech32"}

            val dataBytes = dataString.map { c -> charset.indexOf(c).toByte() }.toByteArray()
            val checkBytes = dataString.takeLast(CHECKSUM_SIZE).map { c -> charset.indexOf(c).toByte() }.toByteArray()

            val actualSum = checksum(hrp, dataBytes.dropLast(CHECKSUM_SIZE).toTypedArray())
            require(1 == polymod(expandHrp(hrp).plus(dataBytes.map { d -> d.toInt() }))) { "checksum failed: $checkBytes != $actualSum" }

            return Bech32Data(hrp, dataBytes.dropLast(CHECKSUM_SIZE).toByteArray())
        }

        /**
         * ConvertBits regroups bytes with toBits set based on reading groups of bits as a continuous stream group by fromBits.
         * This process is used to convert from base64 (from 8) to base32 (to 5) or the inverse.
         */
        fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
            require (fromBits in 1..8 && toBits in 1..8) { "only bit groups between 1 and 8 are supported"}

            // resulting bytes with each containing the toBits bits from the input set.
            var regrouped = arrayListOf<Byte>()

            var nextByte = 0.toUByte()
            var filledBits = 0

            data.forEach { d ->
                // discard unused bits.
                var b = (d.toUByte() shl (8 - fromBits))

                // How many bits remain to extract from input data.
                var remainFromBits = fromBits

                while (remainFromBits > 0) {
                    // How many bits remain to be copied in
                    val remainToBits = toBits - filledBits

                    // we extract the remaining bits unless that is more than we need.
                    val toExtract = remainFromBits.takeUnless { remainToBits < remainFromBits } ?: remainToBits
                    check(toExtract >= 0) { "extract should be positive"}

                    // move existing bits to the left to make room for bits toExtract, copy in bits to extract
                    nextByte = (nextByte shl toExtract) or (b shr (8 - toExtract))

                    // discard extracted bits and update position counters
                    b = b shl toExtract
                    remainFromBits -= toExtract
                    filledBits += toExtract

                    // if we have a complete group then reset.
                    if (filledBits == toBits) {
                        regrouped.add(nextByte.toByte())
                        filledBits = 0
                        nextByte = 0.toUByte()
                    }
                }
            }

            // pad any unfinished groups as required
            if (pad && filledBits > 0) {
                nextByte = nextByte shl (toBits - filledBits)
                regrouped.add(nextByte.toByte())
                filledBits = 0
                nextByte = 0.toUByte()
            }

            // check for any incomplete groups that are more than 4 bits or not all zeros
            require (filledBits == 0 || (filledBits <= 4 && nextByte == 0.toUByte())) { "invalid incomplete group" }

            return regrouped.toByteArray()
        }

        /**
         * Encodes data 5-bit bytes (data) with a given human readable portion (hrp) into a bech32 string.
         * @see convertBits for conversion or ideally use the Bech32Data extension functions
         */
        fun encode(hrp: String, fiveBitData: ByteArray): String {
            return (fiveBitData.plus(checksum(hrp, fiveBitData.toTypedArray()))
                .map { b -> charset[b.toInt()] }).joinToString("", hrp + "1")
        }

        /**
         * Calculates a bech32 checksum based on BIP 173 specification
         */
        fun checksum(hrp: String, data: Array<Byte>): ByteArray {
            var values = expandHrp(hrp)
                .plus(data.map { d -> d.toInt() })
                .plus(Array<Int>(6){ _ -> 0}.toIntArray())

            var poly = polymod(values) xor 1

            return (0..5).map {
                ((poly shr (5 * (5-it))) and 31).toByte()
            }.toByteArray()
        }

        /**
         * Expands the human readable prefix per BIP173 for Checksum encoding
         */
        fun expandHrp(hrp: String) =
            hrp.map { c -> c.toInt() shr 5 }
                .plus(0)
                .plus(hrp.map { c -> c.toInt() and 31 })
                .toIntArray()

        /**
         * Polynomial division function for checksum calculation.  For details see BIP173
         */
        fun polymod(values: IntArray): Int {
            var chk = 1
            return values.map {
                var b = chk shr 25
                chk = ((chk and 0x1ffffff) shl 5) xor it
                (0..4).map {
                    if (((b shr it) and 1) == 1) {
                        chk = chk xor gen[it]
                    }
                }
            }.let { chk }
        }
    }
}
