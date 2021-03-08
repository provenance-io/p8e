package io.provenance.engine.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.provenance.p8e.shared.sql.offsetDatetime
import io.p8e.util.configureProvenance
import io.provenance.p8e.shared.sql.jsonb
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import java.time.OffsetDateTime

object DataMigrationTable : IdTable<String>("data_migration") {
    val name = text("name")
    val state: Column<JsonNode> = jsonb("state", ObjectMapper().configureProvenance())
    val created = offsetDatetime("created")
    val updated = offsetDatetime("updated")
    val completed = offsetDatetime("completed").nullable()

    override val id: Column<EntityID<String>> = name.entityId()
}

open class DataMigrationEntityClass : EntityClass<String, DataMigrationRecord<Any>>(
    DataMigrationTable
) {
    private val objectMapper = ObjectMapper()

    fun insert(name: String) = new(name) {
        OffsetDateTime.now().also {
            created = it
            updated = it
        }
        state = objectMapper.createObjectNode()
    }

    fun findForUpdate(name: String) = find { DataMigrationTable.id eq name }.forUpdate().firstOrNull()
}

class DataMigrationRecord<T>(id: EntityID<String>): Entity<String>(id) {
    companion object : DataMigrationEntityClass()

    var name by DataMigrationTable.id
    var state by DataMigrationTable.state
    var created by DataMigrationTable.created
    var updated by DataMigrationTable.updated
    var completed by DataMigrationTable.completed

    val isComplete: Boolean
        get() = completed != null

    fun markComplete() { completed = OffsetDateTime.now() }
    fun touch() { updated = OffsetDateTime.now() }
}
