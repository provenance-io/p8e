package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.proto.Common.Signature
import java.security.KeyPair

abstract class SignerImpl {

    //TODO: Need to reference the KeyPair by a UUID
    abstract fun setKeyId(keyPair: KeyPair)

    abstract fun setKeyId(uuid: String)

    /**
     * sign function implementation will be done by specfic signers.
     */
    abstract fun sign(data: String): Signature

    abstract fun sign(data: Message): Signature

    abstract fun sign(data: ByteArray): Signature
}
