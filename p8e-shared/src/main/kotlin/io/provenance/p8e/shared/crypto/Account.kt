package io.provenance.p8e.shared.crypto

import io.p8e.crypto.Hash
import io.p8e.crypto.Numeric
import io.provenance.engine.crypto.Bech32
import io.provenance.engine.crypto.toBech32Data
import org.kethereum.bip32.generateChildKey
import org.kethereum.bip32.model.ExtendedKey
import org.kethereum.bip32.model.Seed
import org.kethereum.bip32.toExtendedKey
import org.kethereum.crypto.*
import org.kethereum.extensions.toBytesPadded
import org.kethereum.model.*
import org.komputing.kbase58.decodeBase58WithChecksum
import org.komputing.kbip44.BIP44
import org.komputing.kbip44.BIP44Element
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyException

// NOTE: Deserialize a string containing a bech32 pub key with `PublicKey(BigInteger(1, decompressKey(this.toBech32Data().data)))`

/**
 * A string containing a bech32 encoded compressed public key as a PublicKey instance
 */
fun String.asBech32PublicKey() =
    CryptoAPI.curve.decodePoint(
        this.toBech32Data()
            .data.drop(Account.PUBSECP256K1_TM_ENCODING_PREFIX.size).toByteArray()
    ).toPublicKey()


/**
 * An Account is a key pair in a hierarchy of keys contained within a `wallet`.  For Provenance an account will be one of
 * two main types; a root account which holds the primary key pair for a wallet that is used to generate all other accounts,
 * keys and addresses, and a normal account which is generated from a root acccount.
 *
 * Each account type is based on a key pair of eXtended keys [xpub, xprv].  The private key can be zeroed out meaning the
 * account instance can only generate public keys.  Each account can generate two sets of 2^31 addresses (normal, hardened)
 * which are themselves each a derived key pair.
 *
 * WARNING: Unhardened keys should be carefully controlled as these unhardened keys can leak information about the parent.
 * This is why in the BIP44 path specification the Account level is always a hardened key--this prevents leaking the root
 * level key of the account/wallet.
 */
class Account {

    private val extKey: ExtendedKey
    private val mainnet: Boolean
    val hasPrivateKey: Boolean

    // Depth in BIP44 path the extKey is for.
    private val depth: Byte

    // BIP44 Element data
    val hardened: Boolean
    private val accountNumber: Int

    /**
     * Instantiates an account using a serialized BIP32 extended key
     *
     * Format:
     * @see https://github.com/bitcoin/bips/blob/master/bip-0032.mediawiki#serialization-format
     */
    constructor(bip32Serialized: String) {
        synchronized(Account::class.java) {
            val data = try { bip32Serialized.decodeBase58WithChecksum() } catch (e: Exception) {
                // base library may throw an exception that contains the key bytes (very bad) so we trap/throw clean.
                throw KeyException("invalid encoded key or checksum")
            }
            if (data.size != EXTENDED_KEY_SIZE) {
                throw KeyException("invalid extended key")
            }
            val buff = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            // 4 byte: version bytes (mainnet: 0x0488B21E public, 0x0488ADE4 private; testnet: 0x043587CF public, 0x04358394 private)
            val versionBytes = ByteArray(4)
            buff.get(versionBytes)

            hasPrivateKey = when {
                versionBytes.contentEquals(xprv) || versionBytes.contentEquals(tprv) -> true
                versionBytes.contentEquals(xpub) || versionBytes.contentEquals(tpub) -> false
                else -> throw KeyException("invalid version bytes for an extended key")
            }

            mainnet = when {
                versionBytes.contentEquals(xprv) || versionBytes.contentEquals(xpub) -> true
                versionBytes.contentEquals(tprv) || versionBytes.contentEquals(tpub) -> false
                else -> throw KeyException("invalid version bytes for an extended key")
            }

            // 1 byte: depth: 0x00 for master nodes, 0x01 for level-1 derived keys, ....
            depth = buff.get()

            // 4 bytes: the fingerprint of the parent's key (0x00000000 if master key)
            val parent = buff.int

            // 4 bytes: child number. This is ser32(i) for i in xi = xpar/i, with xi the key being serialized. (0x00000000 if master key)
            val sequence = buff.int // BIP44 element with hardening flag.

            hardened = (sequence and BIP44_HARDENING_FLAG) != 0
            accountNumber = if (hardened) (sequence xor BIP44_HARDENING_FLAG) else sequence

            // 32 bytes: the chain code
            val chainCode = ByteArray(PRIVATE_KEY_SIZE)
            buff.get(chainCode)

            // 33 bytes: the public key or private key data (serP(K) for public keys, 0x00 || ser256(k) for private keys)
            val keyPair = if (hasPrivateKey) {
                buff.get() // ignore the leading 0
                val privateBytes = ByteArray(PRIVATE_KEY_SIZE)
                buff.get(privateBytes)
                PrivateKey(privateBytes).toECKeyPair()
            } else {
                val compressedPublicBytes = ByteArray(COMPRESSED_PUBLIC_KEY_SIZE)
                buff.get(compressedPublicBytes)
                val uncompressedPublicBytes = decompressKey(compressedPublicBytes)
                ECKeyPair(
                    PrivateKey(BigInteger.ZERO),
                    PublicKey(BigInteger(1, uncompressedPublicBytes))
                )
            }

            // This check ensures that we do not accidentally use production coins outside of testnet instances
            if (this.depth.toInt() == 2 && accountNumber != 1) {
                require(mainnet) { "Use of TEST coins in mainnet is not allowed." }
            }

            extKey = ExtendedKey(keyPair, chainCode, depth, parent, sequence, versionBytes)
        }
    }

    /**
     * Instantiate an account using an instance of an Extended Key
     * @param extendedKey is an extended key to load into this account, does not need to be a root key.
     */
    constructor(extendedKey: ExtendedKey) : this(extendedKey.serialize())

    /**
     * Instantiate an account using a BIP44 path that descends using the given pathString
     *
     * @param extendedKey is the extended keypair to load into the account.
     * @param pathString is a (relative) path for a child account against the given extended key.  Use a full BIP44 path
     * if instantiating against the ROOT, otherwise remove the higher level segments to make a relative path.
     */
    constructor(extendedKey: ExtendedKey, pathString: String) : this(
        // ensure that path starts with ACCOUNT_PREFIX ... but only once
        BIP44(ACCOUNT_ROOT + pathString.toLowerCase().removePrefix(ACCOUNT_ROOT)).path
            .fold(extendedKey) { current, bip44Element ->
                current.generateChildKey(bip44Element)
            }
    )

    /**
     * Initializes an account starting from a given seed down to a given path.
     * @param seed is based off of a BIP39 Mnemonic of words or equivalent entrophy
     * @param pathString is a BIP32 path string (starting with 'm/') that indicates the key to derive for this instance
     * @param mainnet indicates if the generated account should be a mainnet or testnet account.
     */
    constructor(seed: Seed, pathString: String = PROVENANCE_MAINNET_BIP44_PATH, mainnet: Boolean = true) :
            this(seed.toExtendedKey(testnet = !mainnet), pathString)

    /**
     * A set of human readable designations for the depth of the account in the BIP44 hierarchy.
     */
    enum class AccountType {
        ROOT,               // Root accounts are used for wallets that hold all accounts
        PURPOSE,            // This account type holds accounts grouped by purpose (we only use type 44, coin)
        COIN_TYPE,          // Coin type aggregation of accounts, expect 505 (HASH) and 1 (TEST)
        GENERAL,            // Account type for holding all types of address accounts
        SCOPE,              // An instance of account containing a collection of internal or external addresses (BIP44-'CHANGE' option)
        ADDRESS             // An account (address) for sending or receiving coins (depending on internal or external scope)
    }

    companion object {
        // BIP44 Parameter path:
        //    Specification "m/44'/505'/0'/0/0" == m / purpose' / coinType' / account' / change / addressIndex
        //
        //    Purpose      44
        //    CoinType     505 -- https://github.com/satoshilabs/slips/blob/master/slip-0044.md (1 for test coins)
        //    Account      uint32 `json:"account"`
        //    Change       boolean  0 for 'External', 1 for 'Change' (internal)
        //    AddressIndex uint32 `json:"addressIndex"`
        //
        //    The ' mark denotes a 'hardened' field.

        const val BIP44_HARDENING_FLAG = 0x80000000.toInt()
        internal const val COMPRESSED_PUBLIC_KEY_SIZE = PRIVATE_KEY_SIZE + 1
        internal const val EXTENDED_KEY_SIZE: Int = 78

        // Tendermint unfortunately puts an Amino Codec prefix on their public keys that are bech32 encoded.
        // Prefix is EB5AE982 + length(33).toHex()
        internal val PUBSECP256K1_TM_ENCODING_PREFIX = Numeric.hexStringToByteArray("EB5AE98721")

        // BIP32 defines several 4 byte encoding values to designate the purpose of the encoded extended key
        // 4 byte: version bytes (mainnet: 0x0488B21E public, 0x0488ADE4 private; testnet: 0x043587CF public, 0x04358394 private)
        internal val xprv = byteArrayOf(0x04, 0x88.toByte(), 0xAD.toByte(), 0xE4.toByte())
        internal val xpub = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E.toByte())
        internal val tprv = byteArrayOf(0x04, 0x35.toByte(), 0x83.toByte(), 0x94.toByte())
        internal val tpub = byteArrayOf(0x04, 0x35.toByte(), 0x87.toByte(), 0xCF.toByte())

        // The bit strength standard to use with generateMnemonic(ENTROPHY_BITS, WORDLIST_ENGLISH) for Seed creation
        const val ENTROPHY_BITS = 256

        // The official BIP44 registered value for Provenance HASH coins.
        const val PROVENANCE_COIN = 505

        // The BIP44 path for a Wallet root key.
        const val ACCOUNT_ROOT = "m/"

        // The BIP44 path for the default Provenance coin account
        const val PROVENANCE_MAINNET_BIP44_PATH = "m/44'/505'/0'"

        // The BIP44 path for the default TEST coin account
        const val PROVENANCE_TESTNET_BIP44_PATH = "m/44'/1'/0'"

        fun compressedPublicKey(pub: BigInteger): ByteArray {
            // Curve point is from a decoded big integer of type '4' (indicates an uncompressed value)
            val p = CryptoAPI.curve.decodePoint(byteArrayOf(4.toByte(), *pub.toBytesPadded(PUBLIC_KEY_SIZE)))
            // Format flag for this compressed version is 2 for positive Y, 3 for negative
            val format = if (p.y.testBit(0)) 3 else 2
            return byteArrayOf(format.toByte(), *p.x.toBytesPadded(32))
        }

        fun compressedPublicKey(compressedBytes: ByteArray) : BigInteger {
            val point = CURVE.decodePoint(compressedBytes)
            val encoded = point.encoded()
            return BigInteger(encoded.copyOfRange(1, encoded.size))
        }

    }

    internal fun keyPrefix(): String {
        if (mainnet) {
            return Bech32.PROVENANCE_MAINNET_PUBKEY_PREFIX
        } else {
            return Bech32.PROVENANCE_TESTNET_PUBKEY_PREFIX
        }
    }

    internal fun accountPrefix(): String {
        if (mainnet) {
            return Bech32.PROVENANCE_MAINNET_ACCOUNT_PREFIX
        } else {
            return Bech32.PROVENANCE_TESTNET_ACCOUNT_PREFIX
        }
    }

    /**
     * The bech32 encoded public key for this account.  This value can be decoded into an ECPublicKey
     *
     * @return a compressed public key associated with the current account.
     */
    fun bech32PublicKey(): String =
        byteArrayOf(
            *PUBSECP256K1_TM_ENCODING_PREFIX,
            *compressedPublicKey(this.extKey.keyPair.publicKey.key)
        ).toBech32Data(keyPrefix()).address

    /**
     * Encodes the current account's EC KeyPair Address into a bech32 format address string.
     *
     * @return a bech32 encoded unique identifer for the current account key pair.
     */
    fun bech32Address() = synchronized(Account::class.java) {
        Hash.sha256hash160(compressedPublicKey(this.extKey.keyPair.publicKey.key)).toBech32Data(accountPrefix()).address
    }

    /**
     * Returns the Bech32 encoded 'default' address.
     *
     * Note: If this is a ROOT/PURPOSE/COIN level account; uses default ACCOUNT.
     */
    fun defaultAccountAddress(hardenAddress: Boolean = false): String {
        if (depth < 3) {
            // get the default account ... then the default address of it.
            return this.childAccount().childAccount(hardenAddress = hardened).bech32Address()
        } else if (depth < 5) {
            // get the default address of the account.
            return this.childAccount(hardenAddress = hardenAddress).bech32Address()
        }
        // this is already an 'Address' level account so... verify this instance is the default address.
        require(depth.toInt() == 5) { "invalid account depth" }
        require(this.accountNumber == 0) { "current account is not the default [0]: $accountNumber" }
        require(this.hardened == hardenAddress) { "requested hardened: $hardenAddress does not match key state" }
        return this.bech32Address()
    }

    /**
     * Serialize the account instance into an extended key according to BIP32 format.
     * @param discardPrivateKey is used to discard the current private key data (if available)
     * @return an extended key serialized in base58 encoding.
     */
    fun serialize(discardPrivateKey: Boolean = false) = synchronized(Account::class.java) {
        extKey.serialize(!hasPrivateKey || discardPrivateKey)
    }

    /**
     * The type of this Account
     * @return Reference to the type of Account this is based on the BIP44 path level.
     */
    fun type(): AccountType =
        when (this.depth.toInt()) {
            0 -> AccountType.ROOT
            1 -> AccountType.PURPOSE
            2 -> AccountType.COIN_TYPE
            3 -> AccountType.GENERAL
            4 -> AccountType.SCOPE
            5 -> AccountType.ADDRESS
            else -> throw  KeyException("excessive BIP44 depth for key")
        }

    /**
     * Returns either a GENERAL or ADDRESS Account instance following the PROVENANCE_MAINNET_BIP44_PATH or
     * PROVENANCE_TESTNET_BIP44_PATH values.
     *
     *  - For ROOT, Purpose, and Coin level account aggregates this func will return a new General account level instance.
     *  - For General or Scope level accounts this func will return a new Address level account.
     *
     * @param accountNum is used for the account number at the General or Address level instances returned
     * @param internalAddress is used to indicate the SCOPE (internal/external). Default value recommended.
     * @param hardenAddress is only applied when creating address level instances.  General account and higher
     * are always hardened for safety.  WARNING: Hardened keys should always be used if the extended private/public keys
     * will be shared--unhardened keys can compromise the parent Account's private key!
     *
     * @return an instance of Account wrapping the extended keys for the accountNum account child.
     */
    fun childAccount(
        accountNum: Int = 0,
        internalAddress: Boolean = false,
        hardenAddress: Boolean = true,
        stripPrivateKey: Boolean = false
    ) = synchronized(Account::class.java) {
        when (this.depth.toInt()) {

            // ROOT Wallet... descend tree and make a normal account.
            0 -> Account(
                extKey
                    .generateChildKey(BIP44Element(true, 44))
                    .generateChildKey(BIP44Element(true, if (mainnet) 505 else 1))
                    .generateChildKey(BIP44Element(true, accountNum))
                    .serialize(!hasPrivateKey || stripPrivateKey)
            )

            // Unexpected.. this is a defined purpose account (should be for coins 44!)
            1 -> require(accountNumber == 44) { "Account is for use with BIP44 purpose of 44 only" }.let {
                Account(
                    extKey
                        .generateChildKey(BIP44Element(true, if (mainnet) 505 else 1))
                        .generateChildKey(BIP44Element(true, accountNum))
                        .serialize(!hasPrivateKey || stripPrivateKey)
                )
            }

            // This is coin parent account... it should be for one of the two coin types we support (for test/mainnet)
            2 -> require(accountNumber == 505 && mainnet || accountNumber == 1 && !mainnet)
            { "Coin Wallet Account must be type of 505 (mainnet) or 1 (testnet)" }.let {
                Account(
                    extKey.generateChildKey(BIP44Element(true, accountNum))
                        .serialize(!hasPrivateKey || stripPrivateKey)
                )
            }

            // This is a GENERAL account, we create an address of the specified scope and hardness
            3 -> Account(
                extKey
                    .generateChildKey(BIP44Element(false, if (internalAddress) 1 else 0))
                    .generateChildKey(BIP44Element(hardenAddress, accountNum))
                    .serialize(!hasPrivateKey || stripPrivateKey)
            )

            // Unexpected... SCOPE level... used for BIP44 'CHANGE' address classification.  Typically '0'
            4 -> require(accountNumber == 0 || accountNumber == 1)
            { "Scope must be internal (1) or external (0)" }.let {
                Account(
                    extKey.generateChildKey(BIP44Element(hardened, accountNum))
                        .serialize(!hasPrivateKey || stripPrivateKey)
                )
            }

            // This is an ADDRESS level account which can not be used to create further levels of children.
            5 -> throw KeyException("cannot create child account of address level")
            else -> throw  KeyException("invalid depth for account; check BIP44 path")
        }
    }

    /**
     * Returns a child account of this account using the given BIP44 path.  Current instance must be on the path
     * with a matching number, hardness, and network (mainnet/testnet)
     */
    fun childFromPath(pathString: String): Account {
        // If we are not a ROOT then we need to match up the path and calculate a subpath value to the new child key
        if (depth > 0) {
            val bip44 = BIP44(pathString)
            require(bip44.path[this.depth.toInt() - 1].hardened == this.hardened)
            { "current instance at $depth is hardened=$hardened and does not match provided path" }
            require(bip44.path[this.depth.toInt() - 1].number == this.accountNumber)
            { "current instance at $depth is for number $accountNumber but path is for different account" }
            require(bip44.path.size > (this.depth - 1))
            { "path must be for decedent of current account of depth $depth" }
            return Account(this.extKey, BIP44(bip44.path.drop(this.depth.toInt())).toString())
        } else {
            return Account(this.extKey, pathString)
        }
    }

    /**
     * Gets an EC key pair by dropping the extended key information for the current account instance.
     *
     * @return the EC key pair associated with this account.
     */
    fun getECKeyPair() = ECKeyPair(
        this.extKey.keyPair.privateKey, this.extKey.keyPair.publicKey
    )

    /**
     * Returns the key pair associated with this account instance
     */
    fun getKeyPair() = this.extKey.keyPair

    /**
     * Is this account mainnet
     */
    fun isMainnet() = mainnet
}
