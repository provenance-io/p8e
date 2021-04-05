package io.provenance.engine.util

import io.p8e.crypto.Hash
import io.p8e.proto.Common
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.Contracts
import io.provenance.engine.crypto.Bech32
import io.provenance.metadata.v1.MsgP8eMemorializeContractRequest
import io.provenance.metadata.v1.p8e.SignatureSet
import org.bouncycastle.crypto.digests.RIPEMD160Digest

fun ContractSpec.toProv(): io.provenance.metadata.v1.p8e.ContractSpec = io.provenance.metadata.v1.p8e.ContractSpec.parseFrom(toByteArray())

fun Contracts.Contract.toProv(): io.provenance.metadata.v1.p8e.Contract = io.provenance.metadata.v1.p8e.Contract.parseFrom(toByteArray())

fun Common.Signature.toProv(): io.provenance.metadata.v1.p8e.Signature = io.provenance.metadata.v1.p8e.Signature.parseFrom(toByteArray())

fun Envelope.toProv(prefix: String): MsgP8eMemorializeContractRequest =
    MsgP8eMemorializeContractRequest.newBuilder()
        .setScopeId(this.ref.scopeUuid.toString())
        .setGroupId(this.ref.groupUuid.toString())
        // TODO what is this?
        // .setScopeSpecificationId()
        .setContract(this.contract.toProv())
        .setSignatures(SignatureSet.newBuilder()
            .addAllSignatures(this.signaturesList.map { it.toProv() })
            .build()
        )
        .setInvoker(this.contract.invoker.signingPublicKey.toByteArray().secpPubKeyToBech32(prefix))
        .build()

fun ByteArray.secpPubKeyToBech32(hrpPrefix: String): String {
    require(this.size == 33) { "Invalid Base 64 pub key byte length must be 33 not ${this.size}" }
    require(this[0] == 0x02.toByte() || this[0] == 0x03.toByte()) { "Invalid first byte must be 2 or 3 not  ${this[0]}" }
    val shah256 = Hash.sha256(this)
    val ripemd = shah256.toRIPEMD160()
    require(ripemd.size == 20) { "RipeMD size must be 20 not ${ripemd.size}" }

    return Bech32.encode(hrpPrefix, Bech32.convertBits(ripemd, 8, 5, true))
}

fun ByteArray.toRIPEMD160() = RIPEMD160Digest().let {
    it.update(this, 0, this.size)
    val buffer = ByteArray(it.digestSize)
    it.doFinal(buffer, 0)
    buffer
}
