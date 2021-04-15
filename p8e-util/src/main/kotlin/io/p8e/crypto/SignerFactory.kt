package io.p8e.crypto

import io.p8e.crypto.SignerFactoryParam.PenParam
import io.p8e.crypto.SignerFactoryParam.SmartKeyParam
import java.security.KeyPair

sealed class SignerFactoryParam{
    data class PenParam(val keyPair: KeyPair): SignerFactoryParam()
    data class SmartKeyParam(val uuid: String): SignerFactoryParam()
}

class SignerFactory(smartKeyApiKey: String) {
    private val appApiKey = smartKeyApiKey

    fun getSigner(param: SignerFactoryParam): SignerImpl {
        return when (param) {
            is PenParam -> Pen(param.keyPair)
            is SmartKeyParam -> SmartKeySigner(appApiKey, param.uuid)
        }
    }
}
