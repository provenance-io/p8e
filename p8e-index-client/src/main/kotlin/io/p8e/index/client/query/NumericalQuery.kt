package io.p8e.index.client.query

import io.p8e.index.client.query.NumericalType.EQUAL
import io.p8e.index.client.query.NumericalType.GREATER
import io.p8e.index.client.query.NumericalType.GREATER_EQUAL
import io.p8e.index.client.query.NumericalType.LESS
import io.p8e.index.client.query.NumericalType.LESS_EQUAL
import io.p8e.index.client.query.OperationType.NUMERICAL

infix fun String.equal(value: Long): Operation {
    return NumericalOperation(
        EQUAL,
        this,
        value
    )
}

infix fun String.greaterEq(value: Long): Operation {
    return NumericalOperation(
        GREATER_EQUAL,
        this,
        value
    )
}

infix fun String.greater(value: Long): Operation {
    return NumericalOperation(
        GREATER,
        this,
        value
    )
}

infix fun String.lessEq(value: Long): Operation {
    return NumericalOperation(
        LESS_EQUAL,
        this,
        value
    )
}

infix fun String.less(value: Long): Operation {
    return NumericalOperation(
        LESS,
        this,
        value
    )
}

class NumericalOperation(
    val operation: NumericalType,
    val field: String,
    val value: Long
): Operation() {
    override val type = NUMERICAL
}

enum class NumericalType(val operation: String) {
    EQUAL("="),
    GREATER(">"),
    GREATER_EQUAL(">="),
    LESS("<"),
    LESS_EQUAL("<=")
}