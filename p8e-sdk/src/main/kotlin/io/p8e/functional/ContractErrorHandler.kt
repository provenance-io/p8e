package io.p8e.functional

import io.p8e.proxy.ContractError
import io.p8e.spec.P8eContract

@FunctionalInterface
interface ContractErrorHandler<T: P8eContract> {
    fun handle(contractError: ContractError<T>): Boolean
}
