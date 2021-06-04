package io.p8e.index.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.grpc.ManagedChannel
import io.p8e.grpc.client.ChallengeResponseInterceptor
import io.p8e.index.client.query.Query
import io.p8e.proto.Index.ElasticSearchQueryRequest
import io.p8e.proto.Index.FactHistoryRequest
import io.p8e.proto.Index.FactHistoryResponse
import io.p8e.proto.Index.QueryCountResponse
import io.p8e.proto.Index.QueryRequest
import io.p8e.proto.Index.QueryScopeWrapper
import io.p8e.proto.Index.ScopeWrapper
import io.p8e.proto.Index.ScopeWrappers
import io.p8e.proto.Index.ScopesRequest
import io.p8e.proto.IndexServiceGrpc
import io.p8e.util.asJsonNode
import io.p8e.util.toProtoUuidProv
import org.elasticsearch.index.query.QueryBuilder
import java.util.UUID
import java.util.concurrent.TimeUnit

class IndexClient(
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor,
    deadlineMs: Long,
    private val objectMapper: ObjectMapper
) {
    private val client = IndexServiceGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)
        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

    fun query(query: Query): ScopeWrappers {
        return client.query(
            QueryRequest.newBuilder()
                .setJson(objectMapper.writeValueAsString(query))
                .build()
        )
    }

    fun elasticSearchQuery(queryBuilder: QueryBuilder): ScopeWrappers {
        return client.elasticSearchQuery(
            ElasticSearchQueryRequest.newBuilder()
                .setQuery(queryBuilder.toString())
                .build()
        )
    }

    data class QueryResult(val id: String, val fields: JsonNode)
    fun rawQuery(query: Query): List<QueryResult> {
        return client.rawQuery(
            QueryRequest.newBuilder()
                .setJson(objectMapper.writeValueAsString(query))
                .build()
        ).resultsList.map { QueryResult(it.id, it.fieldJson.asJsonNode()) }
    }

    fun elasticSearchRawQuery(queryBuilder: QueryBuilder): List<QueryResult> {
        return client.elasticSearchRawQuery(
            ElasticSearchQueryRequest.newBuilder()
                .setQuery(queryBuilder.toString())
                .build()
        ).resultsList.map { QueryResult(it.id, it.fieldJson.asJsonNode()) }
    }

    fun findLatestScopeByUuid(
        scopeUuid: UUID
    ): ScopeWrapper? {
        return client.findLatestScopeByUuid(scopeUuid.toProtoUuidProv())
            .takeIf { it != ScopeWrapper.getDefaultInstance() }
    }

    fun findLatestScopesByUuids(
        scopesUuids: List<UUID>
    ): ScopeWrappers {
        return client.findLatestScopesByUuids(
            ScopesRequest.newBuilder()
                .addAllUuids(scopesUuids.map { it.toProtoUuidProv() })
                .build()
        )
    }

    fun queryCount(query: Query): QueryCountResponse {
        return client.queryCount(
            QueryRequest.newBuilder()
                .setJson(objectMapper.writeValueAsString(query))
                .build()
        )
    }

    fun queryWithCount(query: Query): QueryScopeWrapper {
        return client.queryWithCount(
            QueryRequest.newBuilder()
                .setJson(objectMapper.writeValueAsString(query))
                .build()
        )
    }

    fun factHistory(
        request: FactHistoryRequest
    ): FactHistoryResponse {
        return client.getFactHistory(
            request
        )
    }
}
