package io.p8e.crypto

import com.google.protobuf.Message
import io.p8e.proto.Common.Signature
import java.security.KeyPair

open class SignerImpl {

    //TODO: Need to reference the KeyPair by a UUID
    open fun setKeyId(keyPair: KeyPair) { /*no-op*/ }

    open fun setKeyId(uuid: String) { /*no-op*/ }

    /**
     * sign function implementation will be done by specfic signers.
     */
    open fun sign(data: String): Signature = Signature.getDefaultInstance()

    open fun sign(data: Message): Signature = Signature.getDefaultInstance()

    open fun sign(data: ByteArray): Signature = Signature.getDefaultInstance()
}
