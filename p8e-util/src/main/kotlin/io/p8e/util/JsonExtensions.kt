package io.p8e.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.hubspot.jackson.datatype.protobuf.ProtobufModule

private object jackson {
    val om = ObjectMapper()
        .registerModules(ProtobufModule(), JavaTimeModule())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
}

private val omWrite get() = jackson.om.writer()
private val omRead get() = jackson.om.reader()

fun String.asJsonNode(): JsonNode = toByteArray().asJsonNode()

fun ByteArray.asJsonNode(): JsonNode = let {
    omRead.readTree(asString()) ?: jackson.om.createObjectNode()
}

fun <T> T.toJsonBytes() = omWrite.writeValueAsBytes(this)

fun <T> T.toJsonString() = omWrite.writeValueAsString(this)
