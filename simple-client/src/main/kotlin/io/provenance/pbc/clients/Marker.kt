package io.provenance.pbc.clients

import feign.Param
import feign.RequestLine
import java.math.BigInteger
import io.provenance.pbc.clients.tx.TxPreparer

data class MarkerAccount(
    val address: String,
    val coins: List<Coin>?,
    val public_key: String?,
    val account_number: Int,
    val sequence: Int,
    val manager: String?,
    val permissions: List<AccessGrant>?,
    val status: String,
    val denom:  String,
    val total_supply: BigInteger = BigInteger.ZERO,
    val marker_type: String
) : Msg()

enum class Permission { mint, burn, deposit, withdraw, delete, grant }
enum class MarkerStatus { undefined, proposed, finalized, active, cancelled, destroyed }

data class AccessGrant(
    val address: String = "",
    val permissions:List<String> = emptyList()
)

data class Asset(
        val address: String = "",
        val scope_id:List<String>? = null
)

data class SupplyRequest(
    val base_req: BaseReq,
    val amount: Coin,
    val recipient: String? = null
) : Msg()

data class StatusChangeRequest(
    val base_req: BaseReq,
    val new_status: String
) : Msg()

data class NewMarkerRequest(
    val base_req: BaseReq,
    val supply: String,
    val manager: String,
    val marker_type: String
) : Msg()

data class MarkerAccessRequest(
    val base_req: BaseReq,
    val address: String,
    val grant: String
) : Msg()

interface Markers {
    //
    // Queries
    //

    // Returns a list of all markers
    @RequestLine("GET /marker/all")
    fun getAll(): AminoQuery<Array<MarkerAccount>>

    // Returns a list of all the addresses that are holding the given marker denom/address
    @RequestLine("GET /marker/holders/{id}?page={page}&limit={limit}&status={status}")
    fun holders(
            @Param("id") id: String,
            @Param("page") page: Int = 0,
            @Param("limit") limit: Int = 200,//mirroring what is in golang
            @Param("status") status: String = ""
    ): AminoQuery<Array<AccountInfo>>

    // Returns the marker for a given address or denom
    @RequestLine("GET /marker/detail/{id}")
    fun detail(@Param("id") addressOrDenom: String): AminoQuery<AminoAny<MarkerAccount>>

    // Returns the permissions assigned to a marker
    @RequestLine("GET /marker/accesscontrol/{id}")
    fun accessControls(@Param("id") addressOrDenom: String): AminoQuery<Array<AccessGrant>>

    // Returns a list of scope uuids that are assigned to this marker
    @RequestLine("GET /marker/assets/{id}?page={page}&limit={limit}")
    fun assets(
        @Param("id") addressOrDenom: String,
        @Param("page") page: Int = 0,
        @Param("limit") limit: Int = 200 //mirroring what is in golang
    ): AminoQuery<Asset>

    // Returns the coins held in this marker
    @RequestLine("GET /marker/escrow/{id}")
    fun escrow(@Param("id") addressOrDenom: String): AminoQuery<Array<Coin>>

    // Returns the total supply of this marker
    @RequestLine("GET /marker/supply/{id}")
    fun supply(@Param("id") addressOrDenom: String): AminoQuery<BigInteger>

    //
    // Transactions
    //

    @RequestLine("POST /marker/{denom}/mint")
    fun prepareMint(@Param("denom") denom: String, request: SupplyRequest): PreparedStdTx

    @RequestLine("POST /marker/{denom}/burn")
    fun prepareBurn(@Param("denom") denom: String, request: SupplyRequest): PreparedStdTx

    @RequestLine("POST /marker/{denom}/status")
    fun prepareSetStatus(@Param("denom") denom: String, request: StatusChangeRequest): PreparedStdTx

    @RequestLine("POST /marker/{denom}/create")
    fun prepareCreate(@Param("denom") denom: String, request: NewMarkerRequest): PreparedStdTx

    @RequestLine("POST /marker/{denom}/withdraw")
    fun prepareWithdraw(@Param("denom") denom: String, request: SupplyRequest): PreparedStdTx

    @RequestLine("POST /marker/{denom}/grant")
    fun prepareGrant(@Param("denom") denom: String, request:MarkerAccessRequest): PreparedStdTx

    @RequestLine("POST /marker/{denom}/revoke")
    fun prepareRevoke(@Param("denom") denom: String, request: MarkerAccessRequest): PreparedStdTx
}

fun Markers.mint(marker: Coin): TxPreparer = { base ->
    log.trace("mint(name:${marker.denom} amount:${marker.amount})")
    prepareMint(marker.denom, SupplyRequest(base, marker))
}

fun Markers.burn(marker: Coin): TxPreparer = { base ->
    log.trace("burn(name:${marker.denom} amount:${marker.amount})")
    prepareBurn(marker.denom, SupplyRequest(base, marker))
}

fun Markers.withdraw(id: String, amount: Coin, recipientAddress: String): TxPreparer = { base ->
    log.trace("withdraw(name: $id amount:$amount)")
    prepareWithdraw(id, SupplyRequest(base, amount, recipientAddress))
}

fun Markers.withdraw(id: String, amount: Coin): TxPreparer = { base ->
    log.trace("withdraw(name: $id amount:$amount)")
    prepareWithdraw(id, SupplyRequest(base, amount))
}

fun Markers.setStatus(id: String, status: MarkerStatus): TxPreparer = { base ->
    log.trace("set_status(name:${id} new_status:${status.name})")
    prepareSetStatus(id, StatusChangeRequest(base, status.name))
}

fun Markers.create(supply:Coin, managerAddr: String, markerType: String): TxPreparer = { base ->
    log.trace("create(name:${supply.denom} type:$markerType supply:${supply.amount} managed_by: $managerAddr)")
    prepareCreate(supply.denom, NewMarkerRequest(base, supply.amount, managerAddr, markerType))
}

fun Markers.grantAccess(id: String, address: String, permissions: List<Permission>): TxPreparer = { base ->
    val perms = permissions.joinToString(",")
    log.trace("grant_access(name:${id} address:${address}, permissions: $perms )")
    prepareGrant(id, MarkerAccessRequest(base, address, perms))
}

fun Markers.revokeAccess(id: String, address: String): TxPreparer = { base ->
    log.trace("revoke_access(name:${id} address:$address)")
    prepareRevoke(id, MarkerAccessRequest(base, address, ""))
}
