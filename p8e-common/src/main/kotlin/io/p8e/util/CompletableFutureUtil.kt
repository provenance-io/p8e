package io.p8e.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlin.concurrent.thread

object CompletableFutureUtil {
    fun <T: Any?> completableFuture(executor: ExecutorService, fn: () -> T): CompletableFuture<T> {
        val completableFuture = CompletableFuture<T>()
        thread(start = false) {
            try {
                completableFuture.complete(fn())
            } catch(t: Throwable) {
                completableFuture.completeExceptionally(t)
            }
        }.let(executor::submit)
        return completableFuture
    }
}