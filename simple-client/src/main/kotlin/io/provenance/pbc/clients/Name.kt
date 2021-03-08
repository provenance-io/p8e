package io.provenance.pbc.clients

import feign.Param
import feign.RequestLine
import io.provenance.pbc.clients.tx.TxPreparer

data class NameRecord(val name: String, val address: String, val restricted: Boolean, val pointer: String?)
data class MultiNameRecords(val records: List<NameRecord>?)

data class BindNameRequest(
        val base_req: BaseReq,
        val name: String,
        val address: String,
        val root: String,
        val restricted: Boolean
) : Msg()

data class BindPointerRequest(
        val base_req: BaseReq,
        val name: String,
        val pointer: String
) : Msg()

data class UnbindPointerRequest(
        val base_req: BaseReq,
        val name: String
)

interface Names {
    //
    // Queries
    //

    @RequestLine("GET /name/{name}")
    fun resolve(@Param("name") name: String): AminoQuery<NameRecord>

    @RequestLine("GET /name/{address}/names")
    fun reverseLookup(@Param("address") address: String): AminoQuery<MultiNameRecords>

    @RequestLine("GET /name/{address}/pointer")
    fun reversePointerLookup(@Param("address") address: String): AminoQuery<MultiNameRecords>

    //
    // Transactions
    //

    @RequestLine("POST /name")
    fun prepareBind(request: BindNameRequest): PreparedStdTx

    @RequestLine("POST /name/pointer")
    fun preparePointer(request: BindPointerRequest): PreparedStdTx

    @RequestLine("DELETE /name/pointer")
    fun prepareDeletePointer(request: UnbindPointerRequest): PreparedStdTx
}

/**
 * Name module transactions.
 */

fun Names.bindName(name: String, address: String, root: String, restricted: Boolean): TxPreparer = { base ->
    log.trace("bindName(name:$name address:$address root:$root restricted:$restricted)")
    prepareBind(BindNameRequest(base, name, address, root, restricted))
}

fun Names.bindPointer(name: String, pointer: String): TxPreparer = { base ->
    log.trace("bindPointer(name:$name pointer:$pointer)")
    preparePointer(BindPointerRequest(base, name, pointer))
}

fun Names.unbindPointer(name: String): TxPreparer = { base ->
    log.trace("unbindPointer(name:$name)")
    prepareDeletePointer(UnbindPointerRequest(base, name))
}
