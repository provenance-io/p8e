package io.provenance.p8e.shared.domain

import io.provenance.p8e.shared.sql.offsetDatetime
import io.provenance.p8e.shared.util.proto
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.append
import org.jetbrains.exposed.sql.jodatime.CurrentDateTime
import org.jetbrains.exposed.sql.or
import p8e.Jobs
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

enum class JobStatus {
    CREATED,
    IN_PROGRESS,
    ERROR,
    COMPLETE
}

class DateAddSeconds(private val dateExp: Expression<OffsetDateTime>, private val addSeconds: Expression<Int>) : Expression<OffsetDateTime>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(dateExp, " + (", addSeconds, "||' SECONDS')::interval")
    }
}

object JobTable : UUIDTable("jobs", "uuid") {
    val payload = proto("payload", Jobs.P8eJob.getDefaultInstance())
    val status = enumerationByName("status", 256, JobStatus::class).default(JobStatus.CREATED)
    val retryAfterSeconds = integer("retry_after_seconds").default(30)
    val created = offsetDatetime("created").clientDefault { OffsetDateTime.now() }
    val updated = offsetDatetime("updated").clientDefault { OffsetDateTime.now() }
}

open class JobEntityClass : UUIDEntityClass<JobRecord>(JobTable) {
    fun create(job: Jobs.P8eJob, retryAfterInterval: Duration = Duration.ofSeconds(30)) = new {
        payload = job
        retryAfterSeconds = retryAfterInterval.toSeconds().toInt()
    }

    fun pollOne(): JobRecord? = find {
        (JobTable.status eq JobStatus.CREATED) or (
            (JobTable.status inList listOf(JobStatus.IN_PROGRESS, JobStatus.ERROR)) and
            (CurrentDateTime() greater DateAddSeconds(JobTable.updated, JobTable.retryAfterSeconds))
        )
    }.forUpdate().firstOrNull()?.also {
        it.status = JobStatus.IN_PROGRESS
        it.updated = OffsetDateTime.now()
    }
}

class JobRecord(uuid: EntityID<UUID>) : UUIDEntity(uuid) {
    companion object : JobEntityClass()

    var uuid by JobTable.id
    var payload: Jobs.P8eJob by JobTable.payload
    var status by JobTable.status
    var retryAfterSeconds by JobTable.retryAfterSeconds
    val created by JobTable.created
    var updated by JobTable.updated

    fun complete() {
        updated = OffsetDateTime.now()
        status = JobStatus.COMPLETE
    }

    fun error() {
        updated = OffsetDateTime.now()
        status = JobStatus.ERROR
    }
}
