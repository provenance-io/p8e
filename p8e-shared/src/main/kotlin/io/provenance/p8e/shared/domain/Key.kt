package io.provenance.p8e.shared.domain

import java.security.PublicKey
import java.util.*

data class ExternalKeyRef(val uuid: UUID, val publicKey: PublicKey)
