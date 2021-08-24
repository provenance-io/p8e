package io.provenance.engine.service

import com.google.protobuf.Message
import io.p8e.util.ThreadPoolFactory
import io.p8e.util.toHex
import io.p8e.util.toPublicKey
import io.provenance.p8e.shared.domain.JobRecord
import io.provenance.p8e.shared.domain.JobStatus
import io.provenance.p8e.shared.extension.logger
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import p8e.Jobs
import java.lang.IllegalArgumentException
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

@Component
class JobService(private val jobHandlerServiceFactory: JobHandlerServiceFactory) {
    companion object {
        private val MAX_CONCURRENT_JOBS = 5
        private val EXECUTORS = ThreadPoolFactory.newFixedThreadPool(MAX_CONCURRENT_JOBS, "job-executor-%d")
    }

    private val log = logger()

    @Scheduled(fixedDelay = 500)
    fun executeJobs() {
        do {
            val jobs = transaction { JobRecord.poll(MAX_CONCURRENT_JOBS) }.map {
                CompletableFuture<Void>().also { future ->
                    thread(start = false) {
                        with(it) {
                            try {
                                jobHandlerServiceFactory(payload).handle(payload)
                                transaction { complete() }
                            } catch (t: Throwable) {
                                log.error("Job ${id.value} failed", t)
                                transaction { error() }
                            } finally {
                                future.complete(null)
                            }
                        }
                    }.also(EXECUTORS::submit)
                }
            }.map { it.get() }
        } while (jobs.isNotEmpty())
    }
}

interface JobHandlerService {
    fun handle(payload: Jobs.P8eJob): Unit
}

typealias JobHandlerServiceFactory = (Jobs.P8eJob) -> JobHandlerService
