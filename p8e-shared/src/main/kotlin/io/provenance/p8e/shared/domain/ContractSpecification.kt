package io.provenance.p8e.shared.domain

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.util.UUID

typealias CST = ContractSpecificationTable

object ContractSpecificationTable : UUIDTable("contract_spec_mapping", columnName = "uuid") {
    val hash = text("hash")
    val provenanceHash = text("provenance_hash")
    val scopeSpecificationUuid = uuid("scope_specification_uuid").references(SST.id)
}

open class ContractSpecificationEntityClass: UUIDEntityClass<ContractSpecificationRecord>(CST) {
    fun findByScopeSpecifications(scopeSpecificationUuids: Collection<UUID>) = find { CST.scopeSpecificationUuid inList scopeSpecificationUuids }
}

class ContractSpecificationRecord(uuid: EntityID<UUID>): UUIDEntity(uuid) {
    companion object: ContractSpecificationEntityClass()

    var hash by CST.hash
    var provenanceHash by CST.provenanceHash
    var scopeSpecificationUuid by CST.scopeSpecificationUuid
}
