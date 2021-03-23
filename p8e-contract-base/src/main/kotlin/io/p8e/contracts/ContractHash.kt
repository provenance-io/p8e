package io.p8e.contracts

interface ContractHash {
    fun getClasses(): Map<String, Boolean>
    fun getHash(): String
    // Provides a means to pair ContractHash to ProtoHash implementations so that the pairing can be preferred
    // when resolution takes place during ContractManager::dehydrateSpec. Fully qualified contract names in
    // ContractHash::getClasses must be unique, but fully qualified proto names in ProtoHash::getClasses will often
    // collide.
    fun getUuid(): String
}
