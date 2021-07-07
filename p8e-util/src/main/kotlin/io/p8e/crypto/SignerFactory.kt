package io.p8e.crypto

import io.p8e.crypto.SignerFactoryParam.PenParam
import io.p8e.crypto.SignerFactoryParam.SmartKeyParam
import java.security.KeyPair
import java.security.PublicKey

sealed class SignerFactoryParam{
    data class PenParam(val keyPair: KeyPair): SignerFactoryParam()
    data class SmartKeyParam(val uuid: String, val publicKey: PublicKey): SignerFactoryParam()
}

class SignerFactory(private val smartKeySigner: SmartKeySigner) {
    fun getSigner(param: SignerFactoryParam): SignerImpl {
        return when (param) {
            is PenParam -> Pen(param.keyPair)
            is SmartKeyParam -> smartKeySigner.instance(param.uuid, param.publicKey)
        }
    }
}
