package io.p8e.util

import com.google.protobuf.Message
import io.p8e.annotations.Fact
import io.p8e.client.P8eClient
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.ContractScope.Record
import io.p8e.util.CompletableFutureUtil.completableFuture
import kotlin.streams.toList

class FactPojoHydrator(
    private val client: P8eClient
) {

    fun <T> hydrate(
        scope: Scope,
        clazz: Class<T>
    ): T {
        val constructor = clazz.declaredConstructors
            .filter {
                it.parameters.isNotEmpty() &&
                it.parameters.all { param ->
                    Message::class.java.isAssignableFrom(param.type) &&
                    (param.getAnnotation(Fact::class.java) != null ||
                     param.type == Scope::class.java)
                }
            }
            .takeIf { it.isNotEmpty() }
            .orThrowContractDefinition("Unable to build POJO of type ${clazz.name} because not all constructor params implement ${Message::class.java.name} and have a Fact annotation")
            .firstOrNull {
                it.parameters.any { param ->
                    scope.recordGroupList.flatMap { it.recordsList }.any { record ->
                        (record.resultName == param.getAnnotation(Fact::class.java)?.name &&
                         record.classname == param.type.name) ||
                        param.type == Scope::class.java
                    }
                }
            }
            .orThrowContractDefinition("No constructor params have a matching fact in scope ${scope.uuid.value}")

        val params = constructor.parameters
            .map {
                it.getAnnotation(Fact::class.java)?.name to it.type
            }
            .map { (name, type) ->
                if (type == Scope::class.java) {
                    type to scope
                } else {
                    type to scope.recordGroupList
                        .flatMap { it.recordsList }
                        .find { record ->
                            (record.resultName == name &&
                             record.classname == type.name) ||
                            type == Scope::class.java
                        }
                }
            }.map { (type, record) ->
                when (record) {
                    is Record ->
                    completableFuture(executor) {
                        client.loadProto(
                            record.resultHash,
                            type.name
                        )
                    }
                    is Scope -> completableFuture(executor) { record }
                    else -> null
                }
            }

        return clazz.cast(constructor.newInstance(*params.parallelStream().map { it?.get() }.toList().toTypedArray()))
    }

    companion object {
        val executor = ThreadPoolFactory.newFixedThreadPool(8, "fact-hydrator-%d")
    }
}
