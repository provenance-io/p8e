package io.p8e.util

import com.google.protobuf.Message
import io.p8e.proxy.Function
import io.p8e.proxy.Function1
import io.p8e.proxy.Function2
import io.p8e.proxy.Function3
import io.p8e.proxy.Function4
import io.p8e.proxy.Function5
import io.p8e.proxy.Function6
import io.p8e.proxy.Function7
import io.p8e.spec.P8eContract
import javassist.util.proxy.ProxyFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object MethodUtil {

    private val cache = ConcurrentHashMap<Class<*>, ProxyFactory>()

    fun <OUT: Message, IN: P8eContract> getMethodFact(
        clazz: Class<IN>,
        function: Function<OUT, IN>
    ): String {
        val factFuture = CompletableFuture<String>()
        val proxy = getProxy(clazz, factFuture)

        try {
            function.apply(proxy)
        } catch (ignored: Throwable) { }
        return factFuture.get()
    }

    fun <OUT: Message, IN: P8eContract, ARG1> getMethodFact(
        clazz: Class<IN>,
        function: Function1<OUT, IN, ARG1>
    ): String {
        val factFuture = CompletableFuture<String>()
        val proxy = getProxy(clazz, factFuture)

        try {
            function.apply(proxy, null)
        } catch (ignored: Throwable) { }
        return factFuture.get()
    }

    fun <OUT: Message, IN: P8eContract, ARG1, ARG2> getMethodFact(
        clazz: Class<IN>,
        function: Function2<OUT, IN, ARG1, ARG2>
    ): String {
        val factFuture = CompletableFuture<String>()
        val proxy = getProxy(clazz, factFuture)

        try {
            function.apply(proxy, null, null)
        } catch (ignored: Throwable) { }
        return factFuture.get()
    }

    fun <OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3> getMethodFact(
        clazz: Class<IN>,
        function: Function3<OUT, IN, ARG1, ARG2, ARG3>
    ): String {
        val factFuture = CompletableFuture<String>()
        val proxy = getProxy(clazz, factFuture)

        try {
            function.apply(proxy, null, null, null)
        } catch (ignored: Throwable) { }
        return factFuture.get()
    }

    fun <OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3, ARG4> getMethodFact(
        clazz: Class<IN>,
        function: Function4<OUT, IN, ARG1, ARG2, ARG3, ARG4>
    ): String {
        val factFuture = CompletableFuture<String>()
        val proxy = getProxy(clazz, factFuture)

        try {
            function.apply(proxy, null, null, null, null)
        } catch (ignored: Throwable) { }
        return factFuture.get()
    }

    fun <OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3, ARG4, ARG5> getMethodFact(
        clazz: Class<IN>,
        function: Function5<OUT, IN, ARG1, ARG2, ARG3, ARG4, ARG5>
    ): String {
        val factFuture = CompletableFuture<String>()
        val proxy = getProxy(clazz, factFuture)

        try {
            function.apply(proxy, null, null, null, null, null)
        } catch (ignored: Throwable) { }
        return factFuture.get()
    }

    fun <OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6> getMethodFact(
        clazz: Class<IN>,
        function: Function6<OUT, IN, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6>
    ): String {
        val factFuture = CompletableFuture<String>()
        val proxy = getProxy(clazz, factFuture)

        try {
            function.apply(proxy, null, null, null, null, null, null)
        } catch (ignored: Throwable) { }
        return factFuture.get()
    }

    fun <OUT: Message, IN: P8eContract, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7> getMethodFact(
        clazz: Class<IN>,
        function: Function7<OUT, IN, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7>
    ): String {
        val factFuture = CompletableFuture<String>()
        val proxy = getProxy(clazz, factFuture)

        try {
            function.apply(proxy, null, null, null, null, null, null, null)
        } catch (ignored: Throwable) { }
        return factFuture.get()
    }

    private fun <T: P8eContract> getProxy(
        clazz: Class<T>,
        factFuture: CompletableFuture<String>
    ): T {
        return cache.computeIfAbsent(clazz) {
            ProxyFactory()
                .apply {
                    superclass = clazz
                    interfaces = arrayOf(P8eContract::class.java)
                }
        }.let { proxyFactory ->
            proxyFactory.create(
                arrayOf(),
                arrayOf(),
                FactMethodHandler(factFuture)
            ).let(clazz::cast)
        }
    }
}
