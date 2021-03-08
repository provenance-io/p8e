package io.provenance.pbc.clients

import feign.Param
import feign.RequestLine
import io.provenance.pbc.clients.tx.TxPreparer
import io.provenance.pbc.proto.spec.ContractSpecProtos.ContractSpec

data class SubmitContractSpecRequest(
        val base_req: BaseReq,
        val contract_spec: ContractSpec
) : Msg()

data class QueryContractSpec(
        val contract_spec: ContractSpec
)

interface ContractSpecs {
    //
    // Queries
    //

    @RequestLine("GET /spec/contract/{hash}")
    fun spec(@Param("hash") hash: String): AminoQuery<QueryContractSpec>

    //
    // Transactions
    //

    @RequestLine("POST /spec/contract")
    fun prepareSubmitContractSpec(req: SubmitContractSpecRequest): PreparedStdTx
}

/**
 * Contract spec metadata module transactions.
 */

fun ContractSpecs.addContractSpec(spec: ContractSpec): TxPreparer = { base ->
    log.trace("addContractSpec(name:${spec.definition.name})")
    prepareSubmitContractSpec(SubmitContractSpecRequest(base, spec))
}
