package io.provenance.engine.service

import io.p8e.index.domain.QueryCountResponse
import io.p8e.index.domain.QueryCountWrapper
import io.p8e.proto.Index
import io.p8e.util.toHex
import io.p8e.util.toSha512Hex
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.index.data.IndexScopeRecord
import io.provenance.engine.index.query.Operation
import io.provenance.engine.index.query.equal
import io.provenance.p8e.shared.service.AffiliateService
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.client.core.CountRequest
import org.elasticsearch.client.core.CountResponse
import org.elasticsearch.index.query.BoolQueryBuilder
import org.elasticsearch.index.query.QueryBuilder
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.search.collapse.CollapseBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.security.PublicKey
import java.time.OffsetDateTime
import java.util.UUID

@Component
class IndexService(
    private val esClient: RestHighLevelClient,
    private val affiliateService: AffiliateService
) {
    private val log = logger()

    fun query(
        publicKey: PublicKey,
        queryBuilder: QueryBuilder,
        size: Int = 100,
        from: Int = 0
    ): List<IndexScopeRecord> {
        val results = rawQuery(publicKey, queryBuilder, size, from)
        val scopeUuids = results.hits.map { it.id.toUuidProv() }

        return transaction {
            //TODO: 65k is the database query limit, we need to improve this query as we reach this limit.
            IndexScopeRecord.findLatestByScopeUuids(
                scopeUuids
            ).also { log.info("Found: ${it.size} IndexScopeRecord from ES Query search for ${publicKey.toHex()}") }
        }
    }

    fun rawQuery(
        publicKey: PublicKey,
        queryBuilder: QueryBuilder,
        size: Int = 100,
        from: Int = 0
    ): SearchResponse {
        val indexNames = transaction { arrayOf(affiliateService.getIndexNameByPublicKey(publicKey)) }

        log.info("Running ES query")

        val results = queryElasticSearch(publicKey, queryBuilder, size, from, indexNames)

        log.info("Done querying data, list of results size = ${results.hits.count()}")

        return results
    }

    fun findLatestByScopeUuid(
        scopeUuid: UUID
    ): IndexScopeRecord? = transaction {
        IndexScopeRecord.findLatestByScopeUuid(scopeUuid)
    }

    fun findLatestByScopeUuids(
        scopeUuids: List<UUID>
    ): List<IndexScopeRecord> = transaction {
        IndexScopeRecord.findLatestByScopeUuids(scopeUuids)
    }

    fun queryCount(
        publicKey: PublicKey,
        operation: Operation
    ): QueryCountResponse {
        val indexNames = transaction { arrayOf(affiliateService.getIndexNameByPublicKey(publicKey)) }
        val results = queryElasticSearchCount(publicKey, operation.toQuery(), indexNames)
        return QueryCountResponse(
            results.count,
            results.isTerminatedEarly ?: false,
            results.successfulShards,
            results.skippedShards,
            results.failedShards,
            results.totalShards
        )
    }

    fun findByScopeUuid(
        scopeUuid: UUID,
        startWindow: OffsetDateTime = OffsetDateTime.MIN,
        endWindow: OffsetDateTime = OffsetDateTime.MAX,
        isAsc: Boolean = false
    ): List<IndexScopeRecord> {
        return transaction {
            IndexScopeRecord.findByScopeUuid(
                scopeUuid,
                startWindow,
                endWindow,
                isAsc
            )
        }
    }

    fun queryWithCount(
        publicKey: PublicKey,
        operation: Operation,
        size: Int = 100,
        from: Int = 0
    ): QueryCountWrapper<IndexScopeRecord> {
        val results = queryElasticSearch(publicKey, operation.toQuery(), size, from)
        val scopeUuids = results.hits.map { it.id.toUuidProv() }

        return QueryCountWrapper(findLatestByScopeUuids(scopeUuids), results.hits.totalHits!!.value)
    }

    private fun getDefaultIndexNames() = transaction { affiliateService.getAllIndexNames().toTypedArray() }

    private fun queryElasticSearch(
        publicKey: PublicKey,
        queryBuilder: QueryBuilder,
        size: Int = 100,
        from: Int = 0,
        indexNames: Array<String> = getDefaultIndexNames()
    ): SearchResponse {
        return esClient.search(
            SearchRequest(*indexNames).apply {
                source(
                    SearchSourceBuilder().apply {
                        query(
                            queryBuilder.forKey(publicKey)
                        )
                        distinctScopes()
                        from(from)
                        size(size)
                    }
                )
            },
            RequestOptions.DEFAULT
        )
    }

    private fun queryElasticSearchCount(
        publicKey: PublicKey,
        queryBuilder: QueryBuilder,
        indexNames: Array<String> = getDefaultIndexNames()
    ): CountResponse {
        return esClient.count(
            CountRequest(*indexNames).apply {
                source(
                    SearchSourceBuilder().apply {
                        query(
                            queryBuilder.forKey(publicKey)
                        )
                    }
                )
            },
            RequestOptions.DEFAULT
        )
    }

    private fun QueryBuilder.forKey(publicKey: PublicKey) = BoolQueryBuilder()
        .must(this)
        .must(("p8e.parties.encryptionPublicKeys" equal publicKey.toSha512Hex()).toQuery())

    private fun SearchSourceBuilder.distinctScopes() = collapse(CollapseBuilder("scopeUuid.keyword"))
}
