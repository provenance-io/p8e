package io.provenance.engine.crypto

import io.p8e.crypto.Hash
import io.p8e.crypto.Pen
import io.p8e.util.base64decode
import io.provenance.pbc.clients.StdPubKey
import io.provenance.pbc.clients.StdSignature
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.kethereum.crypto.getCompressedPublicKey
import org.kethereum.crypto.impl.ec.EllipticCurveSigner
import org.kethereum.model.ECKeyPair
import java.security.KeyPair

typealias SignerFn = (ByteArray) -> List<StdSignature>
object PbSigner {
    fun signerFor(keyPair: ECKeyPair): SignerFn = { bytes ->
        bytes.let {
            Hash.sha256(it)
        }.let {
            StdSignature(
                pub_key = StdPubKey("tendermint/PubKeySecp256k1", keyPair.getCompressedPublicKey()),
                signature = EllipticCurveSigner().sign(it, keyPair.privateKey.key, true).encodeAsBTC()
            )
        }.let {
            listOf(it)
        }
    }

    fun signerFor(keyPair: KeyPair): SignerFn = { bytes ->
        bytes.let {
            Hash.sha256(it)
        }.let {
            val privateKey = (keyPair.private as BCECPrivateKey).s
            StdSignature(
                pub_key = StdPubKey("tendermint/PubKeySecp256k1", (keyPair.public as BCECPublicKey).q.getEncoded(true)) ,
                signature = EllipticCurveSigner().sign(it, privateKey, true).encodeAsBTC() // todo: account for signature provider???
            )
        }.let {
            listOf(it)
        }
    }
}

interface SignerMeta {
    val compressedPublicKey: ByteArray
    val sign: SignerFn
}
class ECSignerMeta(keyPair: ECKeyPair) : SignerMeta {
    override val compressedPublicKey: ByteArray = keyPair.getCompressedPublicKey()
    override val sign: SignerFn = PbSigner.signerFor(keyPair)
}
class JavaSignerMeta(keyPair: KeyPair) : SignerMeta {
    override val compressedPublicKey: ByteArray = (keyPair.public as BCECPublicKey).q.getEncoded(true)
    override val sign: SignerFn = PbSigner.signerFor(keyPair)
}
