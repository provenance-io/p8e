package io.p8e.util

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private val <T> Iterable<T>.sizeOrDefault get() = if (this is Collection<*>) size else 10

private val defaultParmapTimeout = Duration.ofSeconds(10)
private val defaultMinThreadCount = 4
private val cpuBufferCount = 2

private val defaultThreadCount = Runtime.getRuntime().availableProcessors().let { cpus ->
    if (cpus < (defaultMinThreadCount + cpuBufferCount)) defaultMinThreadCount else cpus - cpuBufferCount
}.also {
    LoggerFactory.getLogger("defaultThreadPooler").info("defaultThreadCount:$it")
}

private val SHARED_FIXED_SIZE_THREAD_POOL: ExecutorService = ThreadPoolFactory.newFixedThreadPool(defaultThreadCount, "default-parmap-%d")

private fun <T> List<T>.copy(): List<T> = ArrayList(this)

private fun <K, V> Map<K, V>.copy(): Map<K, V> = HashMap(this)

fun <T, R> Iterable<T>.parmapProv(exec: ExecutorService = SHARED_FIXED_SIZE_THREAD_POOL, transform: (T) -> R): List<R> =
    Collections.synchronizedList(ArrayList<R>(sizeOrDefault)).also { dest ->
        exec.invokeAll(this
            .map { Callable { dest += transform(it) } })
            .map { it.get() }
    }.copy()

fun <K : Any, V, R> Map<K, V>.parmapProv(exec: ExecutorService = SHARED_FIXED_SIZE_THREAD_POOL, transform: (K, V) -> R): Map<K, R> =
    Collections.synchronizedMap<K, R>(HashMap()).also { dest ->
        exec.invokeAll(this
            .map { Callable { dest += it.key to transform(it.key, it.value) } })
            .map { it.get(defaultParmapTimeout.toMillis(), TimeUnit.MILLISECONDS) }
    }.copy()
