package io.p8e.util

import io.p8e.proto.ContractScope.Envelope
import com.google.protobuf.Message
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredFunctions

fun Envelope.getUuid() = ref.groupUuid.toUuidProv()
fun Envelope.getExecUuid() = executionUuid.toUuidProv()
fun Envelope.getUuidNullable() = ref.groupUuid.toUuidOrNullProv()
fun Envelope.getExecUuidNullable() = executionUuid.toUuidOrNullProv()
fun Envelope.getPrevExecUuidNullable() = prevExecutionUuid.toUuidOrNullProv()
fun Envelope.getScopeUuid() = ref.scopeUuid.toUuidProv()
fun Envelope.getScopeUuidNullable() = ref.scopeUuid.toUuidOrNullProv()

inline fun <reified R : Message> R?.orDefault() = this ?: R::class.new()

inline fun <reified T : Message> KClass<T>.new(): T {
    val method = declaredFunctions.first { it.name == "newBuilder" && it.parameters.isEmpty() }
    val builder = method.call() as Message.Builder
    return builder.build() as T
}
