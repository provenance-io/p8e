package io.provenance.pbc.clients.jackson

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.google.protobuf.Message

class ProtoSerializer : StdSerializer<Message>(Message::class.java, false) {
    companion object {
        val INSTANCE = ProtoSerializer()
    }

    override fun serialize(value: Message, gen: JsonGenerator, provider: SerializerProvider) {
        gen.writeBinary(value.toByteArray())
    }
}

