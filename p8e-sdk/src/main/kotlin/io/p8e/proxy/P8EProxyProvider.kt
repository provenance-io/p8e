package io.p8e.proxy

import io.p8e.ContractManager
import io.p8e.proto.Contracts.Contract
import io.p8e.spec.P8eContract

interface P8EProxyProvider {
    fun <T: P8eContract> newProxy(
        contractManager: ContractManager,
        contract: Contract,
        contractClass: Class<T>
    ): T
}