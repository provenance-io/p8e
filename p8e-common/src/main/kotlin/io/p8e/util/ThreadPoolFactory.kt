package io.p8e.util

import com.google.common.util.concurrent.ThreadFactoryBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.concurrent.thread

object ThreadPoolFactory {
    fun newFixedThreadPool(size: Int, namingPattern: String): ExecutorService {
        return Executors.newFixedThreadPool(size, ThreadFactoryBuilder().setNameFormat(namingPattern).build())
            .apply {
                Runtime.getRuntime().addShutdownHook(
                    thread(start = false) {
                        shutdown()
                    }
                )
            }
    }

    fun newScheduledThreadPool(size: Int, namingPattern: String): ScheduledExecutorService {
        return Executors.newScheduledThreadPool(size, ThreadFactoryBuilder().setNameFormat(namingPattern).build())
            .apply {
                Runtime.getRuntime().addShutdownHook(
                    thread(start = false) {
                        shutdown()
                    }
                )
            }
    }

    fun newFixedDaemonThreadPool(size: Int, namingPattern: String): ExecutorService {
        return Executors.newFixedThreadPool(size, ThreadFactoryBuilder().setDaemon(true).setNameFormat(namingPattern).build())
            .apply {
                Runtime.getRuntime().addShutdownHook(
                    thread(start = false) {
                        shutdown()
                    }
                )
            }
    }
}