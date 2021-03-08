package io.provenance.pbc.p8e.ext

import io.p8e.proto.PK
import io.p8e.proto.PK.KeyCurve.P256
import io.p8e.proto.PK.KeyCurve.SECP256K1
import io.p8e.proto.PK.KeyType.ELLIPTIC
import io.provenance.pbc.proto.types.TypesProtos.PublicKeyCurve
import io.provenance.pbc.proto.types.TypesProtos.PublicKeyType

fun PK.KeyType.toPbc(): PublicKeyType = when (this) {
    ELLIPTIC -> PublicKeyType.ELLIPTIC
    else -> throw RuntimeException("Unknonw type: $name (ord:$ordinal)")
}

fun PK.KeyCurve.toPbc(): PublicKeyCurve = when (this) {
    P256 -> PublicKeyCurve.P256
    SECP256K1 -> PublicKeyCurve.SECP256K1
    else -> throw RuntimeException("Unknown type: $name (ord:$ordinal)")
}
