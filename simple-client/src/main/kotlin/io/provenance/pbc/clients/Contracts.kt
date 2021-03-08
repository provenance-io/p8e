package io.provenance.pbc.clients

import com.fasterxml.jackson.databind.JsonNode
import feign.Param
import feign.RequestLine
import io.provenance.pbc.clients.tx.TxPreparer
import io.provenance.pbc.proto.contract.ContractProtos.Contract
import io.provenance.pbc.proto.contract.ContractProtos.Recitals
import io.provenance.pbc.proto.contract.ScopeProtos.Scope
import io.provenance.pbc.proto.types.TypesProtos.SignatureSet
import java.util.UUID

data class MemorializeContractRequest(
        val base_req: BaseReq,
        val scope_id: UUID,
        val group_id: UUID,
        val execution_id: UUID,
        val contract: Contract,
        val signatures: SignatureSet,
        val scope_ref_id: UUID
) : Msg()

data class OwnershipChangeRequest(
        val base_req: BaseReq,
        val scope_id: UUID,
        val group_id: UUID,
        val execution_id: UUID,
        val recitals: Recitals?,
        val contract: Contract?,
        val signatures: SignatureSet,
        val invoker: String
) : Msg()

data class QueryAddressOwnerScope(
        val address: String,
        val scope_id: List<String>
)

data class QueryScope(
        val scope: Scope
)

data class SetValueOwnerRequest(
        val base_req: BaseReq, // Must contain the from address of the current owner
        val scope_id: UUID,
        val to_address: String
)

data class MigrateValueOwnerRequest(
        val base_req: BaseReq,
        val scope_id: UUID
)

interface Contracts {
    //
    // Queries
    //

    @RequestLine("GET /metadata/scope/{uuid}")
    fun scope(@Param("uuid") uuid: UUID): AminoQuery<QueryScope>

    @RequestLine("GET /metadata/ownership/{address}?page={page}&limit={limit}")
    fun scope(
        @Param("address") address: String,
        @Param("page") page: Int = 1,
        @Param("limit") limit: Int = 200 //mirroring what is in golang
    ): AminoQuery<QueryAddressOwnerScope>

    @RequestLine("GET /metadata/valueowner/{address}?page={page}&limit={limit}")
    fun valueOwnerScopes(
        @Param("address") address: String,
        @Param("page") page: Int = 1,
        @Param("limit") limit: Int = 200
    ): AminoQuery<QueryAddressOwnerScope>

    //
    // Transactions
    //

    @RequestLine("POST /metadata/contract")
    fun prepareMemorializeContract(req: MemorializeContractRequest): PreparedStdTx

    @RequestLine("POST /metadata/ownership")
    fun prepareChangeOwnership(req: OwnershipChangeRequest): PreparedStdTx

    @RequestLine("POST /metadata/valueowner")
    fun prepareSetValueOwner(req: SetValueOwnerRequest): PreparedStdTx

    @RequestLine("POST /metadata/valueownermigrate")
    fun prepareMigrateValueOwner(req: MigrateValueOwnerRequest): PreparedStdTx
}

/**
 * Contract metadata module transactions.
 */

fun Contracts.memorializeContract(
        scopeId: UUID,
        groupId: UUID,
        executionId: UUID,
        contract: Contract,
        signatureSet: SignatureSet,
        scopeRefId: UUID
): TxPreparer = { base ->
    log.trace("submitContract(exec:${contract.definition.name})")
    prepareMemorializeContract(MemorializeContractRequest(base, scopeId, groupId, executionId, contract, signatureSet, scopeRefId))
}

fun Contracts.changeScopeOwnership(
        scopeId: UUID,
        groupId: UUID,
        executionId: UUID,
        recitals: Recitals?,
        contract: Contract?,
        signatureSet: SignatureSet,
        invoker: String
): TxPreparer = { base ->
    log.trace("changeOwnership(scope:${scopeId})")
    prepareChangeOwnership(OwnershipChangeRequest(base, scopeId, groupId, executionId, recitals, contract, signatureSet, invoker))
}

fun Contracts.setValueOwner(
    scopeId: UUID,
    newOwnerAddress: String
): TxPreparer = { base ->
    log.trace("setValueOwner(scope:${scopeId})")
    prepareSetValueOwner(SetValueOwnerRequest(base, scopeId, newOwnerAddress))
}

fun Contracts.migrateValueOwner(scopeId: UUID): TxPreparer = { base ->
    log.trace("migrateValueOwner(scope:${scopeId})")
    prepareMigrateValueOwner(MigrateValueOwnerRequest(base, scopeId))
}
