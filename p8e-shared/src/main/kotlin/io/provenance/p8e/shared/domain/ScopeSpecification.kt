package io.provenance.p8e.shared.domain

import com.fasterxml.jackson.databind.ObjectMapper
import io.p8e.util.configureProvenance
import io.provenance.p8e.shared.sql.jsonb
import io.provenance.p8e.shared.sql.offsetDatetime
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.time.OffsetDateTime
import java.util.UUID

typealias SST = ScopeSpecificationTable

object ScopeSpecificationTable : UUIDTable("scope_specification_definition", columnName = "uuid") {
    val name = text("name")
    val description = text("description")
    val partiesInvolved = jsonb<Array<String>>("parties_involved", ObjectMapper().configureProvenance())
    val websiteUrl = text("website_url")
    val iconUrl = text("icon_url")
    val created = offsetDatetime("created")
    val updated = offsetDatetime("updated")
}

open class ScopeSpecificationEntityClass: UUIDEntityClass<ScopeSpecificationRecord>(SST) {
    fun findByName(name: String) = find { SST.name eq name }.firstOrNull()

    fun findByNames(names: Collection<String>) = find { SST.name inList names }

    fun insertOrUpdate(name: String, lambda: (ScopeSpecificationRecord) -> Unit) = findByName(name)?.let(lambda)
        ?: new {
            this.name = name
            this.created = OffsetDateTime.now()

            lambda(this)
        }
}

class ScopeSpecificationRecord(uuid: EntityID<UUID>): UUIDEntity(uuid) {
    companion object: ScopeSpecificationEntityClass()

    var name by SST.name
    var description by SST.description
    var partiesInvolved by SST.partiesInvolved
    var websiteUrl by SST.websiteUrl
    var iconUrl by SST.iconUrl
    var created by SST.created
    var updated by SST.updated
}
