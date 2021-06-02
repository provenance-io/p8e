package io.p8e.engine

import arrow.core.Either
import arrow.core.identity
import com.google.protobuf.Message
import io.p8e.crypto.SignerImpl
import io.p8e.definition.DefinitionService
import io.p8e.proto.Contracts.*
import io.p8e.spec.P8eContract
import io.p8e.util.orThrowContractDefinition
import io.p8e.util.toOffsetDateTimeProv
import io.provenance.p8e.encryption.model.KeyRef
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.security.KeyPair
import java.security.PublicKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlin.concurrent.thread
import kotlin.streams.toList

class ContractWrapper(
    private val executor: ExecutorService,
    private val contractSpecClass: Class<out Any>,
    private val encryptionKeyRef: KeyRef,
    private val definitionService: DefinitionService,
    private val contractBuilder: Contract.Builder,
    private val signer: SignerImpl
) {
    private val facts = buildFacts()

    val contractClass = definitionService.loadClass(
        contractBuilder.definition
    ).takeIf {
        contractSpecClass.isAssignableFrom(it) &&
                P8eContract::class.java.isAssignableFrom(it)
    }.orThrowContractDefinition("Contract class ${contractBuilder.definition.resourceLocation.classname} must implement ${contractSpecClass.name} and extend ${P8eContract::class.java.name}")

    private val constructor = getConstructor(contractClass)

    private val constructorParameters = getConstructorParameters(constructor, facts)

    private val contract = (constructor.newInstance(*constructorParameters.toTypedArray()) as P8eContract)
        .also { it.currentTime.set(contractBuilder.startTime.toOffsetDateTimeProv()) }

    val prerequisites = contractBuilder.conditionsBuilderList
        .filter { it.result == ExecutionResult.getDefaultInstance() }
        .map { condition -> condition to getConditionMethod(contract.javaClass, condition.conditionName) }
        .map { (condition, method) -> Prerequisite(contract, condition, method) }

    val functions = contractBuilder.considerationsBuilderList
        .filter { it.result == ExecutionResult.getDefaultInstance() }
        .map { consideration -> consideration to getConsiderationMethod(contract.javaClass, consideration.considerationName) }
        .map { (consideration, method) -> Function(encryptionKeyRef, signer, definitionService, contract, consideration, method, facts) }

    private fun getConstructor(
        clazz: Class<*>
    ): Constructor<*> =
        clazz.declaredConstructors
            .takeIf { it.size == 1 }
            ?.first()
            .orThrowContractDefinition("Class ${clazz.name} must have only one constructor.")

    private fun getConstructorParameters(
        constructor: Constructor<*>,
        facts: List<FactInstance>
    ): List<Any> =
        constructor.parameters
            .map { getParameterFact(it, facts) }
            .filterNotNull()
            .map { it.messageOrCollection.fold(::identity, ::identity) }


    private fun getParameterFact(
        parameter: Parameter,
        facts: List<FactInstance>
    ): FactInstance? {
        return facts.find {
            parameter.getAnnotation(io.p8e.annotations.Fact::class.java)?.name == it.name
        }
    }

    private fun getConditionMethod(
        contractClass: Class<*>,
        name: String
    ): Method {
        return contractClass.methods
            .filter { it.isAnnotationPresent(io.p8e.annotations.Prerequisite::class.java) }
            .find { it.name == name }
            .orThrowContractDefinition("Unable to find method on class ${contractClass.name} with annotation ${io.p8e.annotations.Prerequisite::class.java} with name $name")
    }

    private fun getConsiderationMethod(
        contractClass: Class<*>,
        name: String
    ): Method {
        return contractClass.methods
            .filter { it.isAnnotationPresent(io.p8e.annotations.Function::class.java) }
            .find { it.name == name }
            .orThrowContractDefinition("Unable to find method on class ${contractClass.name} with annotation ${io.p8e.annotations.Function::class.java} with name $name")
    }

    private fun buildFacts(): List<FactInstance> {
        return contractBuilder.inputsList
            .filter { it.dataLocation.ref.hash.isNotEmpty() }
            .toFactInstance(encryptionKeyRef)
            .takeIf { facts -> facts.map { it.name }.toSet().size == facts.size }
            .orThrowContractDefinition("Found duplicate fact messages by name.")
    }

    private fun List<Fact>.toFactInstance(
        encryptionKeyRef: KeyRef
    ): List<FactInstance> {
        val factMap: Map<String, List<Message>> = groupByTo(mutableMapOf(), { it.name }) { fact ->
            val completableFuture = CompletableFuture<Message>()
            thread(start = false) {
                definitionService.forThread {
                    try {
                        completableFuture.complete(
                            definitionService.loadProto(
                                encryptionKeyRef,
                                fact,
                                signer
                            )
                        )
                    } catch (t: Throwable) {
                        completableFuture.completeExceptionally(t)
                    }
                }
            }.let(executor::submit)
            completableFuture
        }.map { (name, futures) ->
            name to futures.parallelStream().map { it.get() }.toList()
        }.toMap()

        val facts = factMap.entries
            .filter { it.value.size == 1 }
            .flatMap { (name, messages) ->
                messages.map { message ->
                    FactInstance(
                        name,
                        message.javaClass,
                        Either.Left(message)
                    )
                }
            }.toMutableList()

        factMap.entries
            .filter { it.value.size > 1 }
            .map { (name, messages) ->
                FactInstance(
                    name,
                    messages.first().javaClass,
                    Either.Right(messages)
                )
            }.let(facts::addAll)
        return facts
    }
}
