package io.p8e.crypto

import com.fortanix.sdkms.v1.api.SignAndVerifyApi
import io.p8e.crypto.SignerFactoryParam.PenParam
import io.p8e.crypto.SignerFactoryParam.SmartKeyParam
import java.security.KeyPair
import java.security.PublicKey

sealed class SignerFactoryParam{
    data class PenParam(val keyPair: KeyPair): SignerFactoryParam()
    data class SmartKeyParam(val uuid: String, val publicKey: PublicKey): SignerFactoryParam()
}

class SignerFactory(
    private val signAndVerifyApi: SignAndVerifyApi
) {
    fun getSigner(param: SignerFactoryParam): SignerImpl {
        return when (param) {
            is PenParam -> Pen(param.keyPair)
            is SmartKeyParam -> SmartKeySigner(param.uuid, param.publicKey, signAndVerifyApi)
        }
    }
}
