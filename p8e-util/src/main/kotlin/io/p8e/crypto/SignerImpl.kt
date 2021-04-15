package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.proto.Common.Signature

interface SignerImpl {

    /**
     * signer function implementation will be done by specific signers.
     */
    fun sign(data: String): Signature

    fun sign(data: Message): Signature

    fun sign(data: ByteArray): Signature
}
