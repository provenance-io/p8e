package io.provenance.engine.service

import com.google.protobuf.Message
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

@Component
class JobService(private val jobHandlerServiceFactory: JobHandlerServiceFactory) {
    private val log = logger()

    @Scheduled(fixedDelay = 500)
    fun executeJobs() {
        do {
            val job = transaction { JobRecord.pollOne() }?.apply {
                try {
                    jobHandlerServiceFactory(payload).handle(payload)
                    transaction { complete() }
                } catch (t: Throwable) {
                    log.error("Job ${id.value} failed", t)
                    transaction { error() }
                }
            }
        } while (job != null)
    }
}

interface JobHandlerService {
    abstract fun handle(payload: Jobs.P8eJob): Unit
}

typealias JobHandlerServiceFactory = (Jobs.P8eJob) -> JobHandlerService
