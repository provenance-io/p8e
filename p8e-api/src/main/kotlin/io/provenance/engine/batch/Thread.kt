package io.provenance.engine.batch

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.provenance.p8e.shared.extension.logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

// Copied straight from figure-utils
private const val DEFAULT_MIN_THREAD_COUNT = 4
private const val CPU_BUFFER_COUNT = 2

private val defaultThreadCount = Runtime.getRuntime().availableProcessors()
    .let { cpus -> if (cpus < (DEFAULT_MIN_THREAD_COUNT + CPU_BUFFER_COUNT)) DEFAULT_MIN_THREAD_COUNT else cpus - CPU_BUFFER_COUNT }
    .also { logger("defaultThreadPooler").info("defaultThreadCount:$it") }

private fun ThreadFactory.asFixedPool(size: Int = 8) = Executors.newFixedThreadPool(size, this)

fun threadFactory(name: String? = null): ThreadFactory = ThreadFactoryBuilder().run {
    name?.let { setNameFormat("$name-%d") }
    build()
}

fun fixedSizeThreadPool(name: String? = null, numThreads: Int = defaultThreadCount): ExecutorService =
    threadFactory(name).asFixedPool(numThreads)

fun shutdownHook(fn: () -> Unit) {
    Runtime.getRuntime().addShutdownHook(thread(start = false, block = fn))
}

/**
 * Helper for waiting for all futures to complete.
 */
fun List<Future<*>>.awaitFutures(clazz: KClass<*>) = all {
    try {
        it.get() != null
    } catch (e: Exception) {
        logger().warn("Unexpected exception invoking:${clazz.jvmName}", e)
        true
    }
}
