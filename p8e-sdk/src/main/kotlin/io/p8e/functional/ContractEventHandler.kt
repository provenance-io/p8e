package io.p8e.functional

import io.p8e.proxy.Contract
import io.p8e.spec.P8eContract

@FunctionalInterface
interface ContractEventHandler<T: P8eContract> {
    fun handle(contract: Contract<T>): Boolean
}