package io.p8e.crypto

import io.provenance.engine.crypto.toSignerMeta
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PBSignerTest {
    @Test
    fun `verify SignerImpl (Pen) signerFor signature matches KeyPair signerFor signature`() {
        val keyPair = ProvenanceKeyGenerator.generateKeyPair()
        val penSigner = Pen(keyPair).apply {
            hashType = SignerImpl.Companion.HashType.SHA256
            deterministic = true
        }.toSignerMeta()
        val keyPairSigner = keyPair.toSignerMeta()
        val data = Random.nextBytes(100)

        val penSignature = penSigner.sign(data)[0]
        val keyPairSignature = keyPairSigner.sign(data)[0]

        assert(penSignature.signature.contentEquals(keyPairSignature.signature)) { "Pen Signature does not match KeyPair Signature" }
    }
}
