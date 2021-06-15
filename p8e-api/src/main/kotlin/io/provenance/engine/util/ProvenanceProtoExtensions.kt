package io.provenance.engine.util

import io.p8e.proto.*
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.ContractSpecs.PartyType
import io.p8e.proto.Util
import io.p8e.util.*
import io.provenance.metadata.v1.*
import io.provenance.metadata.v1.PartyType as ProvenancePartyType
import io.provenance.metadata.v1.p8e.SignatureSet
import io.provenance.p8e.shared.domain.ScopeSpecificationRecord
import io.provenance.p8e.shared.service.AffiliateService
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.Long.max

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

fun ContractSpec.toProvHash(): String {
    val provHash = this.toByteArray().sha512().copyOfRange(0, 17)

    // implements the provenance 16 byte hash to metadata address format
    // shift all bytes over 1 and insert a static prefix at index 0
    for (i in 0 until (provHash.size - 1)) {
        provHash[provHash.size - 1 - i] = provHash[provHash.size - 2 - i]
    }
    provHash[0] = PROV_METADATA_PREFIX_CONTRACT_SPEC

    return String(provHash.base64Encode())
}

fun ContractSpec.toProv(): io.provenance.metadata.v1.p8e.ContractSpec = io.provenance.metadata.v1.p8e.ContractSpec.parseFrom(toByteArray())

fun Contracts.Contract.toProv(): io.provenance.metadata.v1.p8e.Contract = io.provenance.metadata.v1.p8e.Contract.parseFrom(toByteArray()).run {
    toBuilder()
        .clearConsiderations()
        .addAllConsiderations(considerationsList.map { consideration ->
            consideration.toBuilder()
                .setConsiderationName(consideration.result.output.name)
                .build()
        }).build()
}

fun Common.Signature.toProv(): io.provenance.metadata.v1.p8e.Signature = io.provenance.metadata.v1.p8e.Signature.parseFrom(toByteArray())

fun Util.UUID.toProv(): io.provenance.metadata.v1.p8e.UUID = io.provenance.metadata.v1.p8e.UUID.parseFrom(toByteArray())

fun Envelope.toProv(invokerAddress: String): MsgP8eMemorializeContractRequest =
    MsgP8eMemorializeContractRequest.newBuilder()
        .setScopeId(this.ref.scopeUuid.value)
        .setGroupId(this.ref.groupUuid.value)
        // TODO refactor name fetch to service with caching?
        // Does this need to verify that this scope specification is associated with this contract hash locally in the db as well?
        .apply {
            transaction { ScopeSpecificationRecord.findByName(scope.scopeSpecificationName)?.id?.value?.toString() }?.let { scopeSpecificationId ->
                setScopeSpecificationId(scopeSpecificationId)
            }
        }
        .setContract(this.contract.toProv().toBuilder()
            .setContext(Contracts.ContractState.newBuilder()
                .setExecutionUuid(this.executionUuid)
                .build().toByteString()
            )
            .build()
        )
        .setSignatures(SignatureSet.newBuilder()
            .addAllSignatures(this.signaturesList.map { it.toProv() })
            .build()
        )
        .setInvoker(invokerAddress)
        .build()

// Extensions for marshalling data back to P8e

fun ScopeResponse.toP8e(contractSpecHashLookup: Map<String, String>, affiliateService: AffiliateService): ContractScope.Scope = ContractScope.Scope.newBuilder()
    .setUuid(scope.scopeIdInfo.scopeUuid.toProtoUuidProv())
    .addAllParties(scope.scope.ownersList.map { it.toP8e(affiliateService) })
    .addAllRecordGroup(sessionsList.map { session ->
        ContractScope.RecordGroup.newBuilder()
            .setSpecification(contractSpecHashLookup.getOrDefault(session.contractSpecIdInfo.contractSpecAddr, session.contractSpecIdInfo.contractSpecAddr))
            .setGroupUuid(session.sessionIdInfo.sessionUuid.toProtoUuidProv())
//            .setExecutor() // TODO: not sure if this is available, no keys appear to be readily accessible
            .addAllParties(session.session.partiesList.map { it.toP8e(affiliateService) })
            .addAllRecords(recordsList
                .filter { record -> record.record.sessionId == session.session.sessionId }
                .map { record -> record.toP8e() }
            )
            .setClassname(session.session.name)
            .setAudit(session.session.audit.toP8e())
            .build()
    })
    .setScopeSpecificationName(transaction { ScopeSpecificationRecord.findById(scope.scopeSpecIdInfo.scopeSpecUuid.toUuidProv())!!.name })
    .setLastEvent(sessionsList.lastSession()?.let { session ->
        ContractScope.Event.newBuilder()
            .setExecutionUuid(Contracts.ContractState.parseFrom(session.session.context).executionUuid)
            .setGroupUuid(session.sessionIdInfo.sessionUuid.toProtoUuidProv())
    })
    .build()

fun List<SessionWrapper>.lastSession(): SessionWrapper? = sortedByDescending {
    max(it.session.audit.createdDate.toOffsetDateTimeProv().toEpochSecond(), it.session.audit.updatedDate.toOffsetDateTimeProv().toEpochSecond())
}.firstOrNull()

fun AuditFields.toP8e(): Util.AuditFields = Util.AuditFields.parseFrom(toByteArray())

fun RecordWrapper.toP8e(): ContractScope.Record = with (record) {
    ContractScope.Record.newBuilder()
        .setName(name)
        .setHash(process.hash)
        .setClassname(process.name)
        .addAllInputs(inputsList.map { it.toP8e() }).apply {
            outputsList.firstOrNull()?.let { output ->
                setResultValue(output.statusValue)
                    .setName(name)
                    .setResultName(name) // todo: could maybe be different than the function (record) name, does the output struct need a name field?
                    .setResultHash(output.hash)
            }
        }
        .build()
}

fun RecordInput.toP8e(): ContractScope.RecordInput = ContractScope.RecordInput.newBuilder()
    .setName(name)
    .setHash(hash)
    .setClassname(typeName) // yes, the typename is the classname
    .setTypeValue(statusValue) // yes, type is now status
    .build()

fun Party.toP8e(affiliateService: AffiliateService): Contracts.Recital = Contracts.Recital.newBuilder()
    .setAddress(addressBytes)
    .setSignerRoleValue(roleValue)
    .apply {
        val bech32 = addressBytes.toStringUtf8()
        transaction { affiliateService.getAffiliateFromBech32Address(bech32) }?.let { affiliate ->
            setSigner(PK.SigningAndEncryptionPublicKeys.newBuilder()
                .setSigningPublicKey(affiliate.publicKey.value.toPublicKeyProto())
                .setEncryptionPublicKey(affiliate.encryptionPublicKey.toPublicKeyProto())
            )
        }
    }
    .build()
