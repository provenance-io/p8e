package io.p8e.util

import io.p8e.annotations.Fact
import javassist.util.proxy.MethodHandler
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

class FactMethodHandler(private val factFuture: CompletableFuture<String>): MethodHandler {
    override fun invoke(
        self: Any?,
        thisMethod: Method?,
        proceed: Method?,
        args: Array<out Any>?
    ): Any {
        thisMethod?.getAnnotation(Fact::class.java)?.name?.let(factFuture::complete)
        return ""
    }

}
