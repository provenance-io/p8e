package io.p8e.engine.extension

import com.google.protobuf.Message
import io.p8e.proto.Common.WithAudience
import io.p8e.util.toByteString
import io.provenance.p8e.encryption.ecies.ECUtils

import java.security.PublicKey

fun <T: Message> T.withAudience(audience: Set<ByteArray>): WithAudience {
    return WithAudience.newBuilder()
        .addAllAudience(audience.map { it.toByteString() })
        .setMessage(toByteString())
        .build()
}

fun ByteArray.withAudience(audience: Set<ByteArray>): WithAudience {
    return WithAudience.newBuilder()
        .addAllAudience(audience.map { it.toByteString() })
        .setMessage(toByteString())
        .build()
}

fun WithAudience.toAudience(): Set<PublicKey> {
    return audienceList.map { ECUtils.convertBytesToPublicKey(it.toByteArray()) }.toSet()
}
