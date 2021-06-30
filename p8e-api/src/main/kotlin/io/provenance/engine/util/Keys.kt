package io.provenance.engine.util

import io.p8e.util.toByteString
import io.provenance.objectstore.locator.Util
import io.provenance.p8e.encryption.ecies.ECUtils
import java.security.PublicKey

fun PublicKey.toPublicKeyProtoOSLocator(): Util.PublicKey =
    Util.PublicKey.newBuilder()
        .setSecp256K1(ECUtils.convertPublicKeyToBytes(this).toByteString())
        .build()
