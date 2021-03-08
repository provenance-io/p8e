package io.provenance.p8e.shared.sql

import com.fasterxml.jackson.databind.ObjectMapper
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Function
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.postgresql.util.PGobject
import kotlin.reflect.KProperty

inline fun <reified T : Any> Table.jsonb(name: String, objectMapper: ObjectMapper): Column<T> =
    registerColumn(name, JsonbColumnType({ objectMapper.writeValueAsString(it) }, { objectMapper.readerFor(T::class.java).readValue(it) }))

class JsonbColumnType<T : Any>(
    private val stringify: (T) -> String,
    private val parse: (String) -> T
) : ColumnType() {
    override fun sqlType() = JSONB

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        super.setParameter(stmt, index, value.let {
            PGobject().apply {
                this.type = sqlType()
                this.value = value as String?
            }
        })
    }

    override fun valueFromDB(value: Any): Any {
        return when (value) {
            is PGobject -> value.value?.let(parse) as Any
            else -> value
        }
    }

    override fun valueToString(value: Any?): String = when (value) {
        is Iterable<*> -> nonNullValueToString(value)
        else -> super.valueToString(value)
    }

    @Suppress("UNCHECKED_CAST")
    override fun notNullValueToDB(value: Any) = stringify(value as T)

    companion object {
        const val JSONB = "JSONB"
        const val TEXT = "TEXT"
    }
}

class JsonValue<T>(
    val expr: Expression<*>,
    override val columnType: ColumnType,
    val jsonPath: List<String>
) : Function<T>(columnType) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        val castJson = columnType.sqlType() != "JSONB"
        if (castJson) append("(")
        append(expr)
        append(" #>")
        if (castJson) append(">")
        append(" '{${jsonPath.joinToString { escapeFieldName(it) }}}'")
        if (castJson) append(")::${columnType.sqlType()}")
    }

    operator fun getValue(entity: Entity<*>, property: KProperty<*>): T? {
        return if (entity.readValues.hasValue(this)) entity.readValues.get(this) else null
    }

    operator fun setValue(entity: Entity<*>, property: KProperty<*>, value: Any?) {
        entity.readValues[this] = value
    }

    companion object {

        private fun escapeFieldName(value: String) = value.map {
            fieldNameCharactersToEscape[it] ?: it
        }.joinToString("").let { "\"$it\"" }

        private val fieldNameCharactersToEscape = mapOf(
            '\"' to "\\\"",
            '\r' to "\\r",
            '\n' to "\\n"
        )
    }
}

inline fun <reified T> Column<*>.jsonValue(vararg jsonPath: String): JsonValue<T> {
    val columnType = when (T::class) {
        Boolean::class -> BooleanColumnType()
        Int::class -> IntegerColumnType()
        Float::class -> FloatColumnType()
        String::class -> TextColumnType()
        else -> JsonbColumnType({ error("Unexpected call") }, { error("Unexpected call") })
    }
    return JsonValue(this, columnType, jsonPath.toList())
}
