package io.provenance.engine.util

import io.p8e.crypto.Hash
import io.p8e.proto.Common
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.ContractSpecs.PartyType
import io.p8e.proto.Contracts
import io.p8e.util.base64Decode
import io.p8e.util.base64Encode
import io.provenance.engine.crypto.Bech32
import io.provenance.metadata.v1.MsgP8eMemorializeContractRequest
import io.provenance.metadata.v1.PartyType as ProvenancePartyType
import io.provenance.metadata.v1.p8e.SignatureSet
import org.bouncycastle.crypto.digests.RIPEMD160Digest

const val PROV_METADATA_PREFIX_CONTRACT_SPEC: Byte = 0x03

fun PartyType.toProv() = when (this) {
    PartyType.SERVICER -> ProvenancePartyType.PARTY_TYPE_SERVICER
    PartyType.ORIGINATOR -> ProvenancePartyType.PARTY_TYPE_ORIGINATOR
    PartyType.OWNER -> ProvenancePartyType.PARTY_TYPE_OWNER
    PartyType.AFFILIATE -> ProvenancePartyType.PARTY_TYPE_AFFILIATE
    PartyType.CUSTODIAN -> ProvenancePartyType.PARTY_TYPE_CUSTODIAN
    PartyType.INVESTOR -> ProvenancePartyType.PARTY_TYPE_INVESTOR
    PartyType.OMNIBUS -> ProvenancePartyType.PARTY_TYPE_OMNIBUS
    PartyType.PROVENANCE -> ProvenancePartyType.PARTY_TYPE_PROVENANCE
    PartyType.MARKER, PartyType.NONE, PartyType.UNRECOGNIZED -> throw IllegalStateException("Invalid PartyType of ${this}.")
}

fun ContractSpec.toProvHash(): String = this.definition.resourceLocation.ref.hash
    .let { sha512 ->
        val provHash = sha512.base64Decode().copyOfRange(0, 17)

        // implements the provenance 16 byte hash to metadata address format
        // shift all bytes over 1 and insert a static prefix at index 0
        for (i in 0 until (provHash.size - 1)) {
            provHash[provHash.size - 1 - i] = provHash[provHash.size - 2 - i]
        }
        provHash[0] = PROV_METADATA_PREFIX_CONTRACT_SPEC

        String(provHash.base64Encode())
    }

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
