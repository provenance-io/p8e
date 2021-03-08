package io.provenance.p8e.encryption.kdf

import io.provenance.p8e.encryption.CryptoException

class ProvenanceHKDFCryptoException (message:String,cause:Throwable): CryptoException(message, cause)
