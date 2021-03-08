package io.provenance.pbc.clients

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException

data class StdSignature(
    val pub_key: StdPubKey,
    val signature: ByteArray
)

@JsonDeserialize(using = PubKeyDeserializer::class)
data class StdPubKey(
    val type: String,
    @JsonAlias("data")
    val value: ByteArray?= ByteArray(0)
)

/**
 * Public key used to be a string, now it is an object, since all Markers are accounts,
 * and they still send the public_key is sent as a empty string.
 * This deserialize just checks that if the json string is empty, it returns a null so that the
 * default value can be used.
 */
internal class PubKeyDeserializer : JsonDeserializer<StdPubKey?>() {
    @Throws(IOException::class, JsonProcessingException::class)
    override fun deserialize(jsonParser: JsonParser, context: DeserializationContext?): StdPubKey? {
        val node: JsonNode = jsonParser.readValueAsTree()
        return if (node.asText().isEmpty() && node.toList().isEmpty()) {
            null
        } else {
            StdPubKey(node.get("type").asText(),node.get("value").asText().toByteArray(Charsets.UTF_8))
        }
    }
}
