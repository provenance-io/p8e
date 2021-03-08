package io.provenance.engine.util

import io.netty.buffer.ByteBufInputStream
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table

fun <T: Table> T.bytes(name: String, length: Int): Column<ByteArray> = registerColumn(name, BinaryColumnType(length))

class BinaryColumnType(val length: Int) : ColumnType() {
    override fun sqlType(): String  = "BYTEA"

    override fun valueFromDB(value: Any): Any =
        when (value) {
            is java.sql.Blob -> value.binaryStream.use { it.readAllBytes() }
            is ByteBufInputStream -> value.use { it.readAllBytes() }
            else -> value
        }

    override fun nonNullValueToString(value: Any): String = when(value) {
        is ByteArray -> value.toString(Charsets.UTF_8)
        else -> "$value"
    }
}
