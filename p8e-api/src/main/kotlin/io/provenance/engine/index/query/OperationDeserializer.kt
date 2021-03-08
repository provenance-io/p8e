package io.provenance.engine.index.query

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import io.p8e.util.orThrow
import io.provenance.engine.index.query.OperationType.AND
import io.provenance.engine.index.query.OperationType.NUMERICAL
import io.provenance.engine.index.query.OperationType.OR
import io.provenance.engine.index.query.OperationType.STRING

class OperationDeserializer(clazz: Class<*>): StdDeserializer<Operation>(clazz) {
    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext
    ): Operation {
        return toOperation(p.codec.readTree(p))
    }

    private fun toOperation(jsonNode: JsonNode): Operation {
        val type = jsonNode["type"]
            .takeIf { it != null && it.isTextual }
            .orThrow { IllegalStateException("Unable to deserialize json for ${Operation::class.java.name}") }
            .let { typeNode ->
                typeNode.asText()
                    .let { typeString ->
                        OperationType.valueOf(typeString)
                    }
            }

        return when (type) {
            AND -> toAnd(jsonNode)
            OR -> toOr(jsonNode)
            NUMERICAL -> toNumerical(jsonNode)
            STRING -> toString(jsonNode)
        }
    }

    private fun toAnd(jsonNode: JsonNode): Operation {
        if (!jsonNode.has("operation1") || !jsonNode.has("operation2")) {
            throw IllegalStateException("Unable to deserialize AND operation without operation1 and operation2 fields.")
        }
        return AndOperation(
            toOperation(jsonNode["operation1"]),
            toOperation(jsonNode["operation2"])
        )
    }

    private fun toOr(jsonNode: JsonNode): Operation {
        if (!jsonNode.has("operation1") || !jsonNode.has("operation2")) {
            throw IllegalStateException("Unable to deserialize AND operation without operation1 and operation2 fields.")
        }
        return OrOperation(
            toOperation(jsonNode["operation1"]),
            toOperation(jsonNode["operation2"])
        )
    }

    private fun toNumerical(jsonNode: JsonNode): Operation {
        if (!jsonNode.has("operation") || !jsonNode.has("field") || !jsonNode.has("value")) {
            throw IllegalStateException("Unable to deserialize NUMERICAL operation without operation, field, and value fields.")
        }
        return NumericalOperation(
            jsonNode["operation"].asText().let { NumericalType.valueOf(it) },
            jsonNode["field"].asText(),
            jsonNode["value"].asLong()
        )
    }

    private fun toString(jsonNode: JsonNode): Operation {
        if (!jsonNode.has("operation") || !jsonNode.has("field") || !jsonNode.has("value")) {
            throw IllegalStateException("Unable to deserialize STRING operation without operation, field, and value fields.")
        }
        return StringOperation(
            jsonNode["operation"].asText().let { StringOperationType.valueOf(it) },
            jsonNode["field"].asText(),
            jsonNode["value"].asText()
        )
    }
}