package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.crypto.ProvenanceSigner.Companion.KeyType.EC
import io.provenance.pbc.clients.StdSignature
import io.p8e.crypto.proto.CryptoProtos
import io.p8e.crypto.proto.CryptoProtos.Address
import io.p8e.crypto.proto.CryptoProtos.AddressType.BECH32
import io.p8e.crypto.proto.CryptoProtos.Key
import io.provenance.p8e.encryption.ecies.ECUtils
import io.p8e.util.toByteString
import io.provenance.engine.crypto.Bech32
import io.provenance.engine.crypto.PbSigner
import io.provenance.engine.crypto.toBech32Data
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.kethereum.model.ECKeyPair
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

interface Signer {
    fun address(): Address

    /**
     * Sign protobuf data.
     */
    fun sign(data: Message) = sign(data.toByteArray())

    /**
     * Sign string data.
     */
    fun sign(data: String) = sign(data.toByteArray())

    /**
     * Sign byte array.
     */
    fun sign(data: ByteArray): CryptoProtos.Signature

    fun signLambda(): (ByteArray) -> List<StdSignature>
}

class ProvenanceSigner(val keypair: KeyPair, val ecKeyPair: ECKeyPair? = null, private val mainNet: Boolean): Signer {
    private val publicKey = asKey(keypair.public, mainNet)

    constructor(keypair: KeyPair, mainNet: Boolean): this(keypair, null, mainNet)

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    companion object {
        enum class KeyType(val algo: String) {
            EC("SHA256withECDSA"),
            ED("Ed25519")
        }

        fun getSignatureProvider(key: java.security.Key) =
            keyType(key).algo.let {
                Signature.getInstance(it, BouncyCastleProvider.PROVIDER_NAME)
            }

        fun keyType(key: java.security.Key) =
            when (key) {
                is ECPublicKey, is ECPrivateKey -> EC
                else -> throw UnsupportedOperationException("Key type not implemented")
            }

        fun asKey(key: PublicKey, mainNet: Boolean) =
            Key.newBuilder().also {
                var keyBytes: ByteArray
                keyType(key).also { keyType ->
                    when (keyType) {
                        EC -> {
                            keyBytes = (key as BCECPublicKey).q.getEncoded(true)
                            it.curve = ECUtils.LEGACY_DIME_CURVE
                        }
                        else -> throw UnsupportedOperationException("Key type not implemented")
                    }
                }

                it.encodedKey = keyBytes.toByteString()
                it.address = getAddress(keyBytes, mainNet)
                it.encoding = "RAW"
            }.build()

        fun getAddress(key: PublicKey, mainNet: Boolean) = getAddress(asKey(key, mainNet).encodedKey.toByteArray(), mainNet)
        fun getAddress(bytes: ByteArray, mainNet: Boolean) =
            bytes.let {
                (ECUtils.convertBytesToPublicKey(it) as BCECPublicKey).q.getEncoded(true)
            }.let {
                Hash.sha256hash160(it)
            }.let {
                mainNet.let {
                    if (it)
                        Bech32.PROVENANCE_MAINNET_ACCOUNT_PREFIX
                    else
                        Bech32.PROVENANCE_TESTNET_ACCOUNT_PREFIX
                }.let { prefix ->
                    it.toBech32Data(prefix).address
                }

            }.let {
                Address.newBuilder().setValue(it).setType(BECH32).build()
            }

        fun verify(data: ByteArray, signature: CryptoProtos.Signature): Boolean {
            val publicKey = signature.publicKey.let {
                ECUtils.convertBytesToPublicKey(it.encodedKey.toByteArray(), it.curve)
            }

            val s = getSignatureProvider(publicKey)
            s.initVerify(publicKey)
            s.update(data)
            return s.verify(signature.signatureBytes.toByteArray())
        }
    }

    override fun address(): Address = getAddress(keypair.public, mainNet)

    /**
     * Sign byte array.
     */
    override fun sign(data: ByteArray): CryptoProtos.Signature {
        val s = getSignatureProvider(keypair.private)
        s.initSign(keypair.private)
        s.update(data)
        val signature = s.sign()

        return CryptoProtos.Signature.newBuilder()
            .setPublicKey(publicKey)
            .setSignatureBytes(signature.toByteString())
            .build()
            .takeIf { verify(data, it) }
            .orThrow { RuntimeException("can't verify signature - public cert may not match private key.") }
    }

    override fun signLambda(): (ByteArray) -> List<StdSignature> {
        require(ecKeyPair != null) { "Signer doesn't implement kethereum BigInteger keypair." }
        return PbSigner.signerFor(ecKeyPair)
    }

    fun signatureBytesToSignature(
        signatureBytes: ByteArray
    ): CryptoProtos.Signature {
        return CryptoProtos.Signature.newBuilder()
            .setPublicKey(publicKey)
            .setSignatureBytes(signatureBytes.toByteString())
            .build()
    }

    private fun <T : Any, X : Throwable> T?.orThrow(supplier: () -> X) = this?.let { it } ?: throw supplier()
}
