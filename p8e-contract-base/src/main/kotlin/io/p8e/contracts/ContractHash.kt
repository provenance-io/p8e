package io.p8e.contracts

interface ContractHash {
    fun getClasses(): Map<String, Boolean>
    fun getHash(): String
}