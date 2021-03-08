package io.provenance.engine.crypto

import io.p8e.crypto.Hash
import io.provenance.pbc.clients.StdPubKey
import io.provenance.pbc.clients.StdSignature
import org.kethereum.crypto.getCompressedPublicKey
import org.kethereum.crypto.impl.ec.EllipticCurveSigner
import org.kethereum.model.ECKeyPair

object PbSigner {
    fun signerFor(keyPair: ECKeyPair): (ByteArray) -> List<StdSignature> = { bytes ->
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
}
