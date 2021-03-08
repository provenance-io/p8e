package io.provenance.pbc.p8e.ext

import com.google.protobuf.Message
import io.p8e.proto.Common.Signature
import io.p8e.proto.PK.PublicKey
import io.p8e.proto.PK.SigningAndEncryptionPublicKeys
import io.provenance.pbc.proto.types.TypesProtos
import io.provenance.pbc.proto.types.TypesProtos.SignatureSet

fun Message.isEmpty(): Boolean = this == defaultInstanceForType

fun List<Signature>.toPbc(): SignatureSet = SignatureSet.newBuilder().also { pbc ->
    pbc.addAllSignatures(map { it.toPbc() })
}.build()

fun Signature.toPbc(): TypesProtos.Signature = TypesProtos.Signature.newBuilder().also { pbc ->
    if (this.isEmpty()) {
        return@also
    }

    pbc.algo = algo
    pbc.provider = provider
    pbc.signature = signature
    pbc.signer = signer.toPbc()
}.build()

fun SigningAndEncryptionPublicKeys.toPbc(): TypesProtos.SigningAndEncryptionPublicKeys = TypesProtos.SigningAndEncryptionPublicKeys.newBuilder().also { pbc ->
    if (this.isEmpty()) {
        return@also
    }

    pbc.encryptionPublicKey = encryptionPublicKey.toPbc()
    pbc.signingPublicKey = signingPublicKey.toPbc()
}.build()

fun PublicKey.toPbc(): TypesProtos.PublicKey = TypesProtos.PublicKey.newBuilder().also { pbc ->
    if (this.isEmpty()) {
        return@also
    }

    pbc.publicKeyBytes = publicKeyBytes
    pbc.curve = curve.toPbc()
    pbc.type = type.toPbc()
}.build()
