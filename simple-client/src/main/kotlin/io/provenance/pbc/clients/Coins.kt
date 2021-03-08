package io.provenance.pbc.clients

import java.math.BigInteger
import kotlin.math.ceil

enum class Denom { vspn, hash }

private fun coin(amount: BigInteger, denom: Denom): Coin = Coin(denom.name, amount.toString())

infix fun String.coins(denom: Denom) = coin(toBigInteger(), denom)
infix fun Int.coins(denom: Denom) = coin(toBigInteger(), denom)
infix fun Long.coins(denom: Denom) = coin(toBigInteger(), denom)
fun Double.roundUp(): Long = ceil(this).toLong()
