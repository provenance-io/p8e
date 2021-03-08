package io.provenance.pbc.clients.tx

import io.provenance.pbc.clients.BaseReq
import io.provenance.pbc.clients.PreparedStdTx
import io.provenance.pbc.clients.StdTx
import io.provenance.pbc.clients.TxContext
import kotlin.reflect.jvm.jvmName

typealias TxPreparer = (BaseReq) -> PreparedStdTx

typealias BatchTx = Tx.() -> Unit
typealias SingleTx = TxContext.() -> TxPreparer

fun batch(block: BatchTx) = block

fun SingleTx.toBatch(): BatchTx = run {
    val t = this
    return { prepare(t) }
}

class Tx(private val txContext: TxContext) {
    private val children = mutableListOf<TxPreparer>()

    fun prepare(child: TxContext.() -> TxPreparer) = children.add(child(txContext))
    fun bind(baseReq: BaseReq): BoundTx = BoundTx(children.map { it to it.invoke(baseReq) })
}

data class BoundTx(private val preparedTxs: List<Pair<TxPreparer, PreparedStdTx>>) {
    private val named = preparedTxs.map { it.first::class.jvmName to it.second }

    val estimates: List<Pair<String, Long>>
        get() {
            require(preparedTxs.all { it.second.isEstimate }) { "not all prepared txns are gas estimates" }
            return named.map { it.first to it.second.asEstimate().gas_estimate.toLong() }
        }

    val txns: List<Pair<String, StdTx>>
        get() {
            require(preparedTxs.all { it.second.isTx }) { "not all prepared txns are stdtx" }
            return named.map { it.first to it.second.asTx() }
        }
}
