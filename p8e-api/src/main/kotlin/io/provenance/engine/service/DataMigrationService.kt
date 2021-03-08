package io.provenance.engine.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.p8e.util.or
import io.p8e.util.timed
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.configureProvenance
import io.provenance.engine.domain.DataMigrationRecord
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime

data class MigrationState<T>(val state: T, val setState: (T) -> Unit, val heartbeat: () -> Unit)

@Component
class DataMigrationService {
    val log = logger()
    val objectMapper = ObjectMapper().configureProvenance()

    /**
     * Run a migration if it has not been run before (or didn't previously finish running after some max age)
     *
     * **Note: the migration lambda is not run inside of a transaction, to allow the migration to handle its own
     * transaction management and avoid holding a transaction open for too long**
     *
     * @param name the name of the migration, used to track this migration's status in the DB
     * @param maxAge the maximum amount of time, after which the migration will be re-attempted if not complete when this method is called
     * @param migration the function to run
     */
    final inline fun <reified T> runMigration(name: String, maxAge: Duration, crossinline migration: (MigrationState<T>) -> Unit) {
        transaction {
            DataMigrationRecord.findForUpdate(name)?.apply {
                if (isComplete) {
                    log.info("Skipping completed migration $name")
                    return@transaction null
                }
                if (inProgressNotExpired(maxAge)) {
                    log.info("Skipping in-progress (not expired) migration $name")
                    return@transaction null
                }
                log.info("Retrying expired migration $name")
                updated = OffsetDateTime.now()
            }.or { DataMigrationRecord.insert(name) }
        }?.also { migrationRecord ->
            try {
                timed("migration_$name") {
                    migration(MigrationState<T>(objectMapper.readerFor(T::class.java).readValue(migrationRecord.state), setState = {
                        transaction {
                            migrationRecord.state = objectMapper.readTree(objectMapper.writeValueAsBytes(it))
                            migrationRecord.touch()
                        }
                    }) { transaction { migrationRecord.touch() } })
                    transaction { migrationRecord.markComplete() }
                }
            } catch (t: Throwable) {
                log.error("Migration $name failed with message ${t.message}")
            }
        }
    }

    fun DataMigrationRecord<*>.inProgressNotExpired(maxAge: Duration) = updated.plusSeconds(maxAge.toSeconds()).isAfter(OffsetDateTime.now())
}
