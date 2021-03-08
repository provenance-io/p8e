package io.provenance.p8e.shared.util

import com.google.protobuf.GeneratedMessageV3
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import org.postgresql.util.PGobject

fun <R : Message, T : Table> T.proto(name: String, message: R): Column<R> =
    registerColumn(name, ProtoColumnType(message))

class ProtoColumnType(val message: Message) : ColumnType() {
    override fun sqlType() = "JSONB"

    override fun valueFromDB(value: Any): Any = when (value) {
        is PGobject -> {
            val builder = message.newBuilderForType()
            jsonParser.merge(value.value, builder)
            builder.build()
        }
        is Message -> value
        is String -> {
            val builder = message.newBuilderForType()
            jsonParser.merge(value, builder)
            builder.build()
        }
        else -> throw RuntimeException("Can't parse object: $value")
    }

    override fun notNullValueToDB(value: Any): Any = when (value) {
        is Message -> {
            val obj = PGobject()
            obj.type = "jsonb"
            obj.value = jsonPrinter.print(value as GeneratedMessageV3)
            obj
        }
        else -> value
    }

    companion object {
        private val jsonParser = JsonFormat.parser().ignoringUnknownFields()
        private val jsonPrinter = JsonFormat.printer()
    }
}
