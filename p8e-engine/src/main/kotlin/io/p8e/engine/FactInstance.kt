package io.p8e.engine

import arrow.core.Either
import com.google.protobuf.Message

data class FactInstance(
    val name: String,
    val clazz: Class<out Message>,
    val messageOrCollection: Either<Message, List<Message>>
)
