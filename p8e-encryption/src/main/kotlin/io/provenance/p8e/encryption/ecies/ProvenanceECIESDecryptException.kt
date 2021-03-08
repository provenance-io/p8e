package io.provenance.p8e.encryption.ecies

import io.provenance.p8e.encryption.CryptoException

class ProvenanceECIESDecryptException(message:String, cause:Throwable): CryptoException(message, cause)
