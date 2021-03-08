package io.provenance.pbc.clients

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import feign.Param
import feign.RequestLine
import io.provenance.pbc.clients.tx.TxPreparer

@JsonIgnoreProperties(ignoreUnknown = true)
data class AccountInfo(
        val address: String = "",
        val coins: List<Coin> = emptyList(),
        val public_key: StdPubKey = StdPubKey("", ByteArray(0)),
        val account_number: Int = 0, // todo: change back to UInt once https://github.com/FasterXML/jackson-module-kotlin/issues/396 fixed
        val sequence: Int = 0 // todo: change back to UInt once https://github.com/FasterXML/jackson-module-kotlin/issues/396 fixed
)

data class AddAttributeRequest(
        val base_req: BaseReq,
        val name: String,
        val value: String,
        val type: String,
        val account: String
) : Msg()

data class DeleteAttributeRequest(
        val base_req: BaseReq,
        val name: String,
        val account: String
) : Msg()

data class AccountAttribute(
        val name: String,
        val value: String,
        val type: String,
        val height: Int
)

data class AccountAttributesReply(
        val account: String,
        val attributes: List<AccountAttribute>?
)


interface Accounts {
    //
    // Queries
    //

    @RequestLine("GET /auth/accounts/{address}")
    fun fetch(@Param("address") address: String): AminoQuery<AminoAny<AccountInfo>>

    @RequestLine("GET /account/{address}/attributes")
    fun attributes(@Param("address") address: String): AminoQuery<AccountAttributesReply>

    @RequestLine("GET /account/{address}/attributes/{name}")
    fun attribute(@Param("address") address: String, @Param("name") name: String): AminoQuery<AccountAttributesReply>

    @RequestLine("GET /account/{address}/scan/{suffix}")
    fun attributeScan(@Param("address") address: String, @Param("suffix") suffix: String): AminoQuery<AccountAttributesReply>

    //
    // Transactions
    //

    @RequestLine("POST /account/attributes")
    fun prepareAddAttribute(request: AddAttributeRequest): PreparedStdTx

    @RequestLine("DELETE /account/attributes")
    fun prepareDeleteAttribute(request: DeleteAttributeRequest): PreparedStdTx
}

/**
 * Account module transactions.
 */

fun Accounts.addAttribute(name: String, value: String, type: String, account: String): TxPreparer = { base ->
    log.trace("addAttribute(name:$name value:$value type:$type account:$account)")
    prepareAddAttribute(AddAttributeRequest(base, name, value, type, account))
}

fun Accounts.deleteAttribute(name: String, account: String): TxPreparer = { base ->
    log.trace("deleteAttribute(name:$name account:$account)")
    prepareDeleteAttribute(DeleteAttributeRequest(base, name, account))
}
