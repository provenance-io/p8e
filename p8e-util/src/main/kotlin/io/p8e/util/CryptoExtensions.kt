package io.p8e.util

import com.fortanix.sdkms.v1.model.KeyObject
import io.p8e.proto.PK
import io.provenance.p8e.encryption.ecies.ECUtils
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import org.bouncycastle.util.encoders.Hex
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

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

/**
 * Convert SmartKey's public key (Sun Security Provider) into a BouncyCastle Provider (P8e).
 *
 * @return [PublicKey] return the Java security version of the PublicKey.
 */
fun KeyObject.toJavaPublicKey() = pubKey
    .let { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(it)) }
    .let { BCECPublicKey(it as ECPublicKey, BouncyCastlePQCProvider.CONFIGURATION) }
    .toPublicKeyProto().toPublicKey()

fun String.toPublicKeyProto(): PK.PublicKey = PK.PublicKey.parseFrom(Hex.decode(this))

fun ByteArray.toPublicKeyProto(): PK.PublicKey = PK.PublicKey.parseFrom(this)

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
