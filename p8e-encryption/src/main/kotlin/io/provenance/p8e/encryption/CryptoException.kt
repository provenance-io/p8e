package io.provenance.p8e.encryption

import java.lang.IllegalArgumentException

open class CryptoException(override val message: String, override val cause: Throwable) : RuntimeException()

open class ProvenanceIvVectorException(override val message: String) : IllegalArgumentException()
