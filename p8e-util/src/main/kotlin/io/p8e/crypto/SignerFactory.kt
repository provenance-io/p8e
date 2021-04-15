package io.p8e.crypto

import java.lang.IllegalStateException
import java.security.KeyPair

class SignerFactory(
    smartKeyApiKey: String
){
    private val appApiKey = smartKeyApiKey

    fun getSigner(uuid: String? = null, keyPair: KeyPair? = null): SignerImpl {
        return if(keyPair != null) {
            Pen(keyPair)
        } else if(!uuid.isNullOrEmpty()) {
            SmartKeySigner(appApiKey, uuid)
        } else{
            throw IllegalStateException("Unable to determine which signer to use.")
        }
    }
}
