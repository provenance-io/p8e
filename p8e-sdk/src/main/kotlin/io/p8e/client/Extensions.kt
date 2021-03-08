package io.p8e.client

import kotlin.concurrent.thread

fun shutdownHook(fn: () -> Unit) {
    Runtime.getRuntime().addShutdownHook(thread(start = false, block = fn))
}

internal fun <T : AutoCloseable> T.closeOnShutdown() = apply { shutdownHook { close() } }



