package io.provenance.p8e.webservice.domain

import io.p8e.util.base64String
import io.p8e.util.toJavaPublicKey
import io.provenance.p8e.encryption.ecies.ECUtils
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey

data class ApiPublicKey(
    val hexPublicKey: String,
    val curve: String = ECUtils.LEGACY_DIME_CURVE,
    val hexPrivateKey: String? = null
) {
    val publicKey = (hexPublicKey.toJavaPublicKey() as BCECPublicKey).q.getEncoded(
        true
    ).base64String()
}
