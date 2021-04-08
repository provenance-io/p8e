package io.provenance.engine.util

import io.p8e.proto.Common
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.ContractSpecs.PartyType
import io.p8e.proto.Contracts
import io.p8e.util.base64Decode
import io.p8e.util.base64Encode
import io.provenance.metadata.v1.MsgP8eMemorializeContractRequest
import io.provenance.metadata.v1.PartyType as ProvenancePartyType
import io.provenance.metadata.v1.p8e.SignatureSet
import io.provenance.p8e.shared.domain.ScopeSpecificationRecord
import org.jetbrains.exposed.sql.transactions.transaction

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

fun Envelope.toProv(invokerAddress: String): MsgP8eMemorializeContractRequest =
    MsgP8eMemorializeContractRequest.newBuilder()
        .setScopeId(this.ref.scopeUuid.value)
        .setGroupId(this.ref.groupUuid.value)
        // TODO refactor name fetch to service with caching?
        // Does this need to verify that this scope specification is associated with this contract hash locally in the db as well?
        .setScopeSpecificationId(transaction { ScopeSpecificationRecord.findByName(scope.scopeSpecificationName)?.id?.value?.toString() })
        .setContract(this.contract.toProv())
        .setSignatures(SignatureSet.newBuilder()
            .addAllSignatures(this.signaturesList.map { it.toProv() })
            .build()
        )
        .setInvoker(invokerAddress)
        .build()
