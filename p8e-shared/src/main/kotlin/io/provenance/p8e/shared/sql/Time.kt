package io.provenance.p8e.shared.sql

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import java.time.OffsetDateTime
import java.time.ZoneId

fun <T : Table> T.offsetDatetime(name: String): Column<OffsetDateTime> = registerColumn(name, OffsetDateTimeColumnType())

class OffsetDateTimeColumnType : ColumnType() {
    override fun sqlType(): String = "TIMESTAMPTZ"

    override fun valueFromDB(value: Any): Any = when (value) {
        is java.sql.Date -> OffsetDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault())
        is java.sql.Timestamp -> OffsetDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault())
        else -> value
    }
}
