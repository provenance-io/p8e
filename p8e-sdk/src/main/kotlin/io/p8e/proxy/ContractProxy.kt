package io.p8e.proxy

import com.google.protobuf.Message
import io.p8e.ContractManager
import io.p8e.annotations.Prerequisite
import io.p8e.annotations.Function
import io.p8e.annotations.Fact
import io.p8e.annotations.Input
import io.p8e.proto.Common.Location
import io.p8e.proto.Contracts.Contract
import io.p8e.spec.P8eContract
import io.p8e.util.orThrow
import io.p8e.util.orThrowContractDefinition
import io.p8e.util.orThrowNotFound
import javassist.util.proxy.MethodFilter
import javassist.util.proxy.MethodHandler
import javassist.util.proxy.ProxyFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KFunction

enum class FactType {
    PROPOSED,
    FACT
}
data class MethodReflect(val prerequisite: Prerequisite?, val function: Function?,
                         val parameters: List<Parameter>, val inputs: Map<String, FactType>) {
    fun hasPrerequisite() = prerequisite != null
    fun hasFunction() = function != null
}

class ContractProxy(
    private val contractManager: ContractManager,
    private var contract: Contract
): MethodFilter, MethodHandler {
    private val methodResultCache = ConcurrentHashMap<String, Message>()
    private val methodReflectCache = ConcurrentHashMap<Method, MethodReflect>()
    private val handlers = mapOf<String, KFunction<*>>(
//        "execute" to ::executeHandler,
        "getContract" to ::getContract
    )

    companion object : P8EProxyProvider {
        override fun <T: P8eContract> newProxy(
            contractManager: ContractManager,
            contract: Contract,
            contractClass: Class<T>
        ): T {
            val contractProxy = ContractProxy(contractManager, contract)

            val factory = ProxyFactory()
            factory.superclass = contractClass
            factory.setFilter(contractProxy)

            val constructor = contractClass.declaredConstructors.first()
            val args = getConstructorParameters(
                contractManager,
                constructor,
                contract
            )

            val result = factory.create(
                args.map { it.javaClass }.toTypedArray(),
                args.toTypedArray(),
                contractProxy
            )

            return contractClass.cast(result)
        }

        private fun getConstructorParameters(
            contractManager: ContractManager,
            constructor: Constructor<*>,
            contract: Contract
        ): List<Any> {
            val paramNamesToType = constructor.parameters
                .map { param ->
                    param.getAnnotation(Fact::class.java)
                        .orThrowContractDefinition("All constructor parameters for ${constructor.declaringClass} must be annotated with ${Fact::class.java.name}")
                        .name to param.type
                }

            val inputs = contract.inputsList
                .filter { it.dataLocation != Location.getDefaultInstance() }
                .groupBy { it.name }
                .filter { (name, facts) ->
                    paramNamesToType.any { (paramName, clazz) ->
                        name == paramName && clazz.name == facts.firstOrNull()?.dataLocation?.classname
                    }
                }
                .mapValues {(_, facts) ->
                    facts.map { fact ->
                        contractManager.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname)
                    }
                }

            val listInputs = inputs.filter { it.value.size > 1 }
            val singleInputs = inputs.filter { it.value.size == 1 }
                .mapValues { it.value.first() }

            return paramNamesToType.mapNotNull { (name, _) ->
                listInputs[name] ?: singleInputs[name]
            }
        }
    }

    fun cache(m: Method): MethodReflect =
        methodReflectCache.getOrPut(m) {
            val prerequisite = m.getAnnotation(Prerequisite::class.java)
            val function = m.getAnnotation(Function::class.java)

            val parameters = m.parameters.map { it }

            val inputs = mutableMapOf<String, FactType>().apply {
                putAll(m.parameters.filter { it.isAnnotationPresent(Fact::class.java) }.map { it.name to FactType.FACT }.toMap())
                putAll(m.parameters.filter { it.isAnnotationPresent(Input::class.java) }.map { it.name to FactType.PROPOSED }.toMap())
            }

            MethodReflect(prerequisite, function, parameters, inputs)
        }

    override fun isHandled(m: Method?) =  m != null &&
        (handlers.containsKey(m.name) || cache(m).hasFunction() || cache(m).hasPrerequisite())

    fun executeHandler(self: Any?,
                       thisMethod: Method,
                       proceed: Method?,
                       args: Array<out Any>?) : Any? {
        self as P8eContract
        return CompletableFuture<P8eContract>()
    }

    fun getContract(self: Any?,
                    thisMethod: Method,
                    proceed: Method?,
                    args: Array<out Any>?) : Any? {
        self as P8eContract
        return this.contract
    }

    fun handlePrerequisite(self: Any?,
                           thisMethod: Method,
                           proceed: Method?,
                           args: Array<out Any>?) : Any? {
        self as P8eContract
        return proceed!!.invoke(self, args)
    }

    fun handleFunction(self: Any?,
                       thisMethod: Method,
                       proceed: Method?,
                       args: Array<out Any>?) : Any? {
        val consideration = contract.considerationsList
            .filter { it.considerationName == thisMethod.name }
            .orThrowNotFound("Unable to find function for ${thisMethod.name}")
            .first()

        // Check to see if the current contract has an output result available.
        val result = consideration
            .takeIf { it.hasResult() }
            ?.let { it.result }
            ?.let { getResult(it.output.name, it.output.hash, it.output.classname) }
            ?: throw IllegalStateException("Contract does not yet have a result for function ${consideration.considerationName}")

        // TODO verify that the input vars match the facts used as inputs in the previous run of the contract. If not error.
        return result
    }

    override fun invoke(
        self: Any?,
        thisMethod: Method?,
        proceed: Method?,
        args: Array<out Any>?
    ): Any? {
        if (self == null || thisMethod == null) {
            return null
        }

        // Call the handler if it's available.
        val handler = handlers[thisMethod.name]
        if (handler != null)
            return handler.call(self, thisMethod, proceed, args)

        val c = cache(thisMethod)
        if (c.hasPrerequisite())
            return handlePrerequisite(self, thisMethod, proceed, args)
        if (c.hasFunction())
            return handleFunction(self, thisMethod, proceed, args)

        return proceed!!.invoke(self, args)
    }

    private fun getResult(
        name: String,
        hash: String,
        classname: String
    ): Message {
        return methodResultCache.computeIfAbsent(name) {
            contractManager.loadProto(hash, classname)
        }
    }

}
