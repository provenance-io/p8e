package io.provenance.pbc.clients.jackson

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.google.protobuf.Message

class ProtoDeserializer<T : Message>(private val t: T) : StdDeserializer<T>(t::class.java) {
    @Suppress("unchecked_cast")
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        return t.parserForType.parseFrom(p.binaryValue) as T
    }
}

