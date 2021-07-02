package io.provenance.p8e.encryption.model

import java.security.PrivateKey
import java.security.PublicKey
import java.util.*

data class ExternalKeyRef(val uuid: UUID, val publicKey: PublicKey)

data class KeyRef(val publicKey: PublicKey, val privateKey: PrivateKey?, val uuid: UUID?, val type: KeyProviders)

enum class KeyProviders {
    DATABASE,
    SMARTKEY,
}
