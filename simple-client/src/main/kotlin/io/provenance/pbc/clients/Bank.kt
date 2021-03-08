package io.provenance.pbc.clients

import feign.Param
import feign.RequestLine
import io.provenance.pbc.clients.tx.TxPreparer

data class TransferFundsRequest(
        val base_req: BaseReq,
        val amount: List<Coin>
) : Msg()

interface Banks {
    @RequestLine("GET /bank/balances/{address}")
    fun balance(@Param("address") address: String): AminoQuery<List<Coin>>

    @RequestLine("POST /bank/accounts/{address}/transfers")
    fun prepareTransfer(@Param("address") address: String, req: TransferFundsRequest): PreparedStdTx
}

/**
 * Transfer funds from your account, [to] another account.
 * @param to The bech32 address to transfer funds to.
 * @param amount The amount of coin to send to your witcher.
 * @param gasAdjustmentOverride Allow overriding the gas adjustment set by client opts. This is needed because the
 *   bank module consistently overestimates by a factor of ~2.3.
 */
fun Banks.transferFunds(to: String, amount: List<Coin>, gasAdjustmentOverride: String = "1.0"): TxPreparer = { base ->
    log.trace("transferFunds(to:$to amount:$amount)")

    // The bank module always over-estimates. So drop the adjustment unless explicitly declared otherwise.
    prepareTransfer(to, TransferFundsRequest(base.copy(gas_adjustment = gasAdjustmentOverride), amount))
}
