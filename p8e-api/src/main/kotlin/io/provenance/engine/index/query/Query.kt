package io.provenance.engine.index.query

import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilder

data class Query(val operation: Operation, val size: Int, val from: Int) {
    constructor(operation: Operation) : this(operation, 100, 0) {
    }
}

interface Operation {
    fun toQuery(): QueryBuilder

    fun and(operation: Operation): Operation {
        return AndOperation(this, operation)
    }

    fun or(operation: Operation): Operation {
        return OrOperation(this, operation)
    }
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
    private val operation1: Operation,
    private val operation2: Operation
): Operation {
    override fun toQuery(): QueryBuilder {
        return BoolQueryBuilder()
            .must(operation1.toQuery())
            .must(operation2.toQuery())
    }
}

class OrOperation(
    private val operation1: Operation,
    private val operation2: Operation
): Operation {
    override fun toQuery(): QueryBuilder {
        return BoolQueryBuilder()
            .should(operation1.toQuery())
            .should(operation2.toQuery())
    }
}
