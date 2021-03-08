package io.p8e.engine

import com.google.protobuf.Message
import io.p8e.annotations.Fact
import io.p8e.proto.Contracts.ConditionProto
import io.p8e.spec.P8eContract
import io.p8e.util.ContractDefinitionException
import java.lang.reflect.Method
import kotlin.Function

class Prerequisite<T: P8eContract>(
    private val contract: T,
    val conditionProto: ConditionProto.Builder,
    val method: Method
): Function<Message> {

    val fact = method.getAnnotation(Fact::class.java)
        ?: throw ContractDefinitionException("${contract.javaClass.name}.${method.name} must have the ${Fact::class.java.name} annotation.")

    operator fun invoke(): Pair<ConditionProto.Builder, Message?> {
        return conditionProto to method.invoke(contract) as? Message
    }
}
