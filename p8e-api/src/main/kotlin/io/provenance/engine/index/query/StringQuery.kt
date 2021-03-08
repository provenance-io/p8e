package io.provenance.engine.index.query

import io.provenance.engine.index.query.StringOperationType.EQUAL
import io.provenance.engine.index.query.StringOperationType.LIKE
import io.provenance.engine.index.query.StringOperationType.REGEXP
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryBuilders
import java.util.UUID

infix fun String.equal(uuid: UUID) = this.equal(uuid.toString())

infix fun String.regexp(pattern: String): Operation {
    return StringOperation(
        REGEXP,
        this,
        pattern
    )
}

infix fun String.equal(value: String): Operation {
    return StringOperation(
        EQUAL,
        this,
        value
    )
}

infix fun String.like(pattern: String): Operation {
    return StringOperation(
        LIKE,
        this,
        pattern
    )
}

class StringOperation(
    private val operation: StringOperationType,
    private val field: String,
    private val value: String
): Operation {
    override fun toQuery(): QueryBuilder {
        return when (operation) {
            EQUAL -> QueryBuilders.matchPhraseQuery(field, value)
            LIKE -> QueryBuilders.wildcardQuery(field, value)
            REGEXP -> QueryBuilders.regexpQuery(field, value)
        }
    }
}

enum class StringOperationType(
    val operation: String
) {
    EQUAL("="),
    LIKE("LIKE"),
    REGEXP("REGEXP")
}
