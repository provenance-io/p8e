package io.provenance.p8e.shared.extension

import com.google.protobuf.Timestamp
import io.p8e.proto.Affiliate
import io.p8e.proto.ContractScope
import io.p8e.proto.Contracts
import io.p8e.util.toOffsetDateTimeProv
import io.p8e.util.toProtoUuidProv
import java.time.OffsetDateTime
import java.util.*

/**
 * Determine if a contract whitelist object is active.
 */
fun Affiliate.AffiliateContractWhitelist.isActive() = OffsetDateTime.now().let { now ->
    !startTime.toOffsetDateTimeProv().isAfter(now) &&
            (!hasEndTime() || endTime.toOffsetDateTimeProv().isAfter(now))
}

/**
 * Find all the record groups by group id.
 *
 * @param [groupUuid] Group identifier to filter by
 */
fun ContractScope.Scope.filterByGroup(groupUuid: UUID): List<ContractScope.RecordGroup> = recordGroupList.filter { it.groupUuid == groupUuid.toProtoUuidProv() }

/**
 * Get all the consideration that are still pending/not complete.
 */
fun ContractScope.Envelope.Builder.pendingConsiderationBuilders(): List<Contracts.ConsiderationProto.Builder> = contractBuilder.considerationsBuilderList
    .filter { it.result != Contracts.ExecutionResult.getDefaultInstance() && it.result.recordedAt == Timestamp.getDefaultInstance() }

/**
 * Record consideration protos with scope group results.
 *
 * @param [recordGroups] Groups to merge with considerations
 */
fun List<Contracts.ConsiderationProto.Builder>.record(recordGroups: List<ContractScope.RecordGroup>) {
    val records = recordGroups.flatMap { it.recordsList.map { record -> Pair(record, it.audit.updatedDate) } }

    forEach { builder ->
        records.firstOrNull { (record, _) ->
            record.resultName == builder.result.output.name &&
            record.hash == builder.result.output.hash &&
            record.classname == builder.result.output.classname &&
            record.name == builder.considerationName
        }
            ?.let { builder.resultBuilder.recordedAt = it.second }
    }
}
