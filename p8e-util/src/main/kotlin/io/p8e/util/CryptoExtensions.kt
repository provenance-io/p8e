package io.p8e.util

import io.p8e.proto.PK
import io.provenance.p8e.encryption.ecies.ECUtils
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.util.encoders.Hex
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security

fun PK.PublicKey.toPublicKey(): PublicKey =
    this.let {
        require(it.curve == PK.KeyCurve.SECP256K1) {"Unsupported Key Curve"}
        ECUtils.convertBytesToPublicKey(it.publicKeyBytes.toByteArray())
    }

fun PublicKey.toPublicKeyProto(): PK.PublicKey =
    PK.PublicKey.newBuilder()
        .setCurve(PK.KeyCurve.SECP256K1)
        .setType(PK.KeyType.ELLIPTIC)
        .setPublicKeyBytes(ECUtils.convertPublicKeyToBytes(this).toByteString())
        .setCompressed(false)
        .build()

fun PK.PublicKey.toHex() = this.toByteArray().toHexString()

fun PublicKey.toHex() = toPublicKeyProto().toHex()

fun PublicKey.toSha512Hex() = toHex().hexStringToByteArray().sha512().toHexString()

fun String.toJavaPublicKey() = toPublicKeyProto().toPublicKey()

fun String.toPublicKeyProto(): PK.PublicKey = PK.PublicKey.parseFrom(Hex.decode(this))

fun PK.PrivateKey.toPrivateKey(): PrivateKey =
    this.let {
        require(it.curve == PK.KeyCurve.SECP256K1) {"Unsupported Key Curve"}
        ECUtils.convertBytesToPrivateKey(it.keyBytes.toByteArray())
    }

fun PrivateKey.toPrivateKeyProto(): PK.PrivateKey =
    PK.PrivateKey.newBuilder()
        .setCurve(PK.KeyCurve.SECP256K1)
        .setType(PK.KeyType.ELLIPTIC)
        .setKeyBytes(ECUtils.convertPrivateKeyToBytes(this).toByteString())
        .build()

fun PK.PrivateKey.toHex() = this.toByteArray().toHexString()

fun PrivateKey.toHex() = toPrivateKeyProto().toHex()

fun PrivateKey.computePublicKey() = ECUtils.toPublicKey(this)!!

fun String.toPrivateKeyProto(): PK.PrivateKey = PK.PrivateKey.parseFrom(Hex.decode(this))

fun String.toJavaPrivateKey() = toPrivateKeyProto().toPrivateKey()

object ECKeyConverter {
    init {
        Security.addProvider(BouncyCastleProvider())
    }

    fun org.kethereum.model.PrivateKey.toJavaPrivateKey(): PrivateKey = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        .generatePrivate(ECPrivateKeySpec(key, ECNamedCurveTable.getParameterSpec("SecP256K1")))
}
