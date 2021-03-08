package io.provenance.engine.util

import io.provenance.os.util.toHexString
import org.jetbrains.exposed.sql.EqOp
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull

infix fun ExpressionWithColumnType<ByteArray>.bytesEqual(bytes: ByteArray?) : Op<Boolean> {
    if (bytes == null) {
        return isNull()
    }
    return EqOp(this, ByteArrayExpression(bytes))
}

class ByteArrayExpression(private val bytes: ByteArray): Expression<ByteArray>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append(
                "decode('${bytes.toHexString()}', 'hex')"
            )
        }
    }
}
