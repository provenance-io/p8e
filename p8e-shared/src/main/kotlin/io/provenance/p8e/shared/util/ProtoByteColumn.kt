package io.provenance.p8e.shared.util

import com.google.protobuf.Message
import io.netty.buffer.ByteBufInputStream
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.Table
import java.io.InputStream
import java.sql.ResultSet

fun <R : Message, T : Table> T.protoBytes(name: String, message: R): Column<R> =
    registerColumn(name, ProtoByteAColumnType(message))

class ProtoByteAColumnType<T: Message>(private val message: T): ColumnType() {

    override fun sqlType() = "bytea"

    override fun notNullValueToDB(value: Any): Any =
        when {
            message::class.java.isInstance(value) -> message::class.java.cast(value).toByteArray()
            value is ByteArray -> value
            else -> throw IllegalStateException("Unknown type when serializing ${message::class.java.canonicalName}: ${value::class.java.canonicalName}")
        }

    override fun valueFromDB(value: Any): Any =
        when (value) {
            is ByteArray -> message.parserForType.parseFrom(value)
            is Message -> value
            is InputStream -> value.use { message.parserForType.parseFrom(it) }
            is ByteBufInputStream -> throw IllegalStateException("Known bug when using ByteBufInputStream inside of valueFromDB due to race between GC and Ref Counting")
            else -> throw IllegalStateException("Unknown type when serializing ${message::class.java.canonicalName}: ${value::class.java.canonicalName}")
        }

    override fun valueToDB(value: Any?): Any? = value?.let { notNullValueToDB(value) }

    override fun readObject(
        rs: ResultSet,
        index: Int
    ): Any? {
        return rs.getBinaryStream(index).use { it.readAllBytes() }
    }
}
