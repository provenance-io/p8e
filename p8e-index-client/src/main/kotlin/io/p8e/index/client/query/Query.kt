package io.p8e.index.client.query

import io.p8e.index.client.query.OperationType.AND
import io.p8e.index.client.query.OperationType.OR

data class Query(val operation: Operation, val size: Int, val from: Int) {
    constructor(operation: Operation) : this(operation, 100, 0) {
    }
}

abstract class Operation {
    fun and(operation: Operation): Operation {
        return AndOperation(this, operation)
    }

    fun or(operation: Operation): Operation {
        return OrOperation(this, operation)
    }

    abstract val type: OperationType
}

enum class OperationType {
    AND,
    OR,
    NUMERICAL,
    STRING
}

infix fun Operation.and(operation: Operation) = this.and(operation)

infix fun Operation.or(operation: Operation) = this.or(operation)

class AndOperation(
    val operation1: Operation,
    val operation2: Operation
): Operation() {
    override val type = AND
}

class OrOperation(
    val operation1: Operation,
    val operation2: Operation
): Operation() {
    override val type = OR
}
