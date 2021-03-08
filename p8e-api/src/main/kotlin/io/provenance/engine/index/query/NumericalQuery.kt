package io.provenance.engine.index.query

import com.google.protobuf.Message
import io.provenance.engine.index.query.NumericalType.EQUAL
import io.provenance.engine.index.query.NumericalType.GREATER
import io.provenance.engine.index.query.NumericalType.GREATER_EQUAL
import io.provenance.engine.index.query.NumericalType.LESS
import io.provenance.engine.index.query.NumericalType.LESS_EQUAL
import org.elasticsearch.index.query.AbstractQueryBuilder
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.index.query.QueryBuilders

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
    private val operation: NumericalType,
    private val field: String,
    private val value: Long
): Operation {
    override fun toQuery(): QueryBuilder {
        return when (operation) {
            EQUAL -> QueryBuilders.termQuery(field, value)
            GREATER -> QueryBuilders.rangeQuery(field)
                .gt(value)
            GREATER_EQUAL -> QueryBuilders.rangeQuery(field)
                .gte(value)
            LESS -> QueryBuilders.rangeQuery(field)
                .lt(value)
            LESS_EQUAL -> QueryBuilders.rangeQuery(field)
                .lte(value)
        }
    }
}

enum class NumericalType(val operation: String) {
    EQUAL("="),
    GREATER(">"),
    GREATER_EQUAL(">="),
    LESS("<"),
    LESS_EQUAL("<=")
}