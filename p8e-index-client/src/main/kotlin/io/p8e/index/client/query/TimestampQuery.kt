package io.p8e.index.client.query

import io.p8e.util.toProtoTimestampProv
import java.time.OffsetDateTime

infix fun String.equal(value: OffsetDateTime): Operation {
    val seconds = "${this}.seconds"
    val nanos = "${this}.nanos"

    val valueTimestamp = value.toProtoTimestampProv()

    // seconds == valueTimestamp.seconds && nanos == valueTimestamp.nanos
    return AndOperation(
        NumericalOperation(
            NumericalType.EQUAL,
            seconds,
            valueTimestamp.seconds
        ),
        NumericalOperation(
            NumericalType.EQUAL,
            nanos,
            valueTimestamp.nanos.toLong()
        )
    )
}

infix fun String.at(value: OffsetDateTime): Operation = this.equal(value)

infix fun String.greaterEq(value: OffsetDateTime): Operation {
    val seconds = "${this}.seconds"
    val nanos = "${this}.nanos"

    val valueTimestamp = value.toProtoTimestampProv()

    // seconds > valueTimestamp.seconds || (seconds == valueTimestamp.seconds && nanos >= valueTimestamp.nanos)
    return OrOperation(
        NumericalOperation(
            NumericalType.GREATER,
            seconds,
            valueTimestamp.seconds
        ),
        AndOperation(
            NumericalOperation(
                NumericalType.EQUAL,
                seconds,
                valueTimestamp.seconds
            ),
            NumericalOperation(
                NumericalType.GREATER_EQUAL,
                nanos,
                valueTimestamp.nanos.toLong()
            )
        )
    )
}

infix fun String.greater(value: OffsetDateTime): Operation {
    val seconds = "${this}.seconds"
    val nanos = "${this}.nanos"

    val valueTimestamp = value.toProtoTimestampProv()

    // seconds > valueTimestamp.seconds || (seconds == valueTimestamp.seconds && nanos > valueTimestamp.nanos)
    return OrOperation(
        NumericalOperation(
            NumericalType.GREATER,
            seconds,
            valueTimestamp.seconds
        ),
        AndOperation(
            NumericalOperation(
                NumericalType.EQUAL,
                seconds,
                valueTimestamp.seconds
            ),
            NumericalOperation(
                NumericalType.GREATER,
                nanos,
                valueTimestamp.nanos.toLong()
            )
        )
    )
}

infix fun String.after(value: OffsetDateTime): Operation = this.greater(value)

infix fun String.lessEq(value: OffsetDateTime): Operation {
    val seconds = "${this}.seconds"
    val nanos = "${this}.nanos"

    val valueTimestamp = value.toProtoTimestampProv()

    // seconds < valueTimestamp.seconds || (seconds == valueTimestamp.seconds && nanos <= valueTimestamp.nanos)
    return OrOperation(
        NumericalOperation(
            NumericalType.LESS,
            seconds,
            valueTimestamp.seconds
        ),
        AndOperation(
            NumericalOperation(
                NumericalType.EQUAL,
                seconds,
                valueTimestamp.seconds
            ),
            NumericalOperation(
                NumericalType.LESS_EQUAL,
                nanos,
                valueTimestamp.nanos.toLong()
            )
        )
    )
}

infix fun String.less(value: OffsetDateTime): Operation {
    val seconds = "${this}.seconds"
    val nanos = "${this}.nanos"

    val valueTimestamp = value.toProtoTimestampProv()

    // seconds < valueTimestamp.seconds || (seconds == valueTimestamp.seconds && nanos < valueTimestamp.nanos)
    return OrOperation(
        NumericalOperation(
            NumericalType.LESS,
            seconds,
            valueTimestamp.seconds
        ),
        AndOperation(
            NumericalOperation(
                NumericalType.EQUAL,
                seconds,
                valueTimestamp.seconds
            ),
            NumericalOperation(
                NumericalType.LESS,
                nanos,
                valueTimestamp.nanos.toLong()
            )
        )
    )
}

infix fun String.before(value: OffsetDateTime): Operation = this.less(value)
