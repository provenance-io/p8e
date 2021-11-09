package io.p8e.index.client.query

import io.p8e.index.client.query.OperationType.STRING
import io.p8e.index.client.query.StringOperationType.EQUAL
import io.p8e.index.client.query.StringOperationType.LIKE
import io.p8e.index.client.query.StringOperationType.NOT_EQUAL
import io.p8e.index.client.query.StringOperationType.REGEXP
import java.util.UUID

infix fun String.equal(uuid: UUID) = this.equal(uuid.toString())

infix fun String.equal(value: String): Operation {
    return StringOperation(
        EQUAL,
        this,
        value
    )
}

infix fun String.notEqual(value: String): Operation {
    return StringOperation(
        NOT_EQUAL,
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

infix fun String.regexp(pattern: String): Operation {
    return StringOperation(
        REGEXP,
        this,
        pattern
    )
}

class StringOperation(
    val operation: StringOperationType,
    val field: String,
    val value: String
): Operation() {
    override val type = STRING
}

enum class StringOperationType {
    EQUAL,
    NOT_EQUAL,
    LIKE,
    REGEXP,
}
