package io.provenance.engine.grpc.v1

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.grpc.stub.StreamObserver
import io.p8e.definition.DefinitionService
import io.p8e.grpc.complete
import io.p8e.grpc.publicKey
import io.p8e.proto.ContractScope
import io.p8e.proto.Index
import io.p8e.proto.Index.ElasticSearchQueryRequest
import io.p8e.proto.Index.FactHistoryRequest
import io.p8e.proto.Index.FactHistoryResponse
import io.p8e.proto.Index.FactHistoryResponseEntry
import io.p8e.proto.Index.QueryCountResponse
import io.p8e.proto.Index.QueryRequest
import io.p8e.proto.Index.QueryScopeWrapper
import io.p8e.proto.Index.ScopeWrapper
import io.p8e.proto.Index.ScopeWrappers
import io.p8e.proto.Index.ScopesRequest
import io.p8e.proto.IndexServiceGrpc.IndexServiceImplBase
import io.p8e.util.ThreadPoolFactory
import io.p8e.util.or
import io.p8e.util.toOffsetDateTimeProv
import io.p8e.util.toUuidProv
import io.p8e.util.toByteString
import io.p8e.util.parmapProv
import io.p8e.util.toJsonString
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.engine.service.IndexService
import io.provenance.p8e.shared.index.data.IndexScopeRecord
import io.provenance.engine.index.query.Query
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.util.P8eMDC
import io.p8e.proto.Util.UUID
import io.provenance.engine.service.ProvenanceGrpcService
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.index.query.QueryBuilders
import org.jetbrains.exposed.sql.transactions.transaction
import org.lognet.springboot.grpc.GRpcService

@GRpcService(interceptors = [JwtServerInterceptor::class, UnhandledExceptionInterceptor::class])
class IndexGrpc(
    private val affiliateService: AffiliateService,
    iOsClient: OsClient,
    private val indexService: IndexService,
    private val objectMapper: ObjectMapper,
    private val provenanceGrpcService: ProvenanceGrpcService,
): IndexServiceImplBase() {
    private val log = logger()

    private val definitionService = DefinitionService(
        iOsClient
    )

    override fun elasticSearchQuery(
        request: ElasticSearchQueryRequest,
        responseObserver: StreamObserver<ScopeWrappers>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        log.debug("Running elasticSearchQuery: ${request.query}")

        val queryBuilder = QueryBuilders.wrapperQuery(request.query)
        indexService.query(
            publicKey(),
            queryBuilder
        ).toScopeWrappers()
        .complete(responseObserver)
    }

    override fun query(
        request: QueryRequest,
        responseObserver: StreamObserver<ScopeWrappers>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        log.debug("Running query: ${request.json}")

        val query = objectMapper.readValue<Query>(request.json)
        indexService.query(
            publicKey(),
            query.operation.toQuery(),
            query.size,
            query.from
        ).toScopeWrappers()
        .complete(responseObserver)
    }

    override fun elasticSearchRawQuery(
        request: ElasticSearchQueryRequest,
        responseObserver: StreamObserver<Index.RawQueryResults>
    ) {
        P8eMDC.set(publicKey(), clear = true)
        log.debug("Running raw elasticSearchQuery:${request.query}")
        val queryBuilder = QueryBuilders.wrapperQuery(request.query)
        indexService.rawQuery(
            publicKey(),
            queryBuilder
        ).toRawQueryResults()
        .complete(responseObserver)
    }

    override fun rawQuery(request: QueryRequest, responseObserver: StreamObserver<Index.RawQueryResults>) {
        P8eMDC.set(publicKey(), clear = true)
        log.debug("Running raw query:${request.json}")
        val query = objectMapper.readValue<Query>(request.json)
        indexService.rawQuery(
            publicKey(),
            query.operation.toQuery(),
            query.size,
            query.from
        ).toRawQueryResults()
        .complete(responseObserver)
    }

    override fun findLatestScopeByUuid(
        scopeUuid: UUID,
        responseObserver: StreamObserver<ScopeWrapper>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        indexService.findLatestByScopeUuid(scopeUuid.toUuidProv())
            ?.toScopeWrapper()
            .or {
                log.debug("Scope not found in database, attempting to fetch from chain")
                try {
                    provenanceGrpcService.retrieveScope(scopeUuid.toUuidProv()).toScopeWrapper()
                } catch (e: Exception) {
                    log.debug("Failed to fetch scope from chain")
                    ScopeWrapper.getDefaultInstance()
                }
            }
            .or { ScopeWrapper.getDefaultInstance() }
            .complete(responseObserver)
    }

    override fun findLatestScopesByUuids(
        request: ScopesRequest,
        responseObserver: StreamObserver<ScopeWrappers>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        val indexedScopes = indexService.findLatestByScopeUuids(request.uuidsList.map { it.toUuidProv() })
            .toScopeWrappers()

        log.debug("Fetched ${indexedScopes.scopesCount}/${request.uuidsCount} scopes from db")

        var allScopes = indexedScopes

        if (indexedScopes.scopesCount != request.uuidsCount) {
            val fetchedUuids = indexedScopes.scopesList.map { it.scope.uuid.toUuidProv() }.toSet()


            val chainScopes = request.uuidsList
                .map { it.toUuidProv() }
                .filterNot { fetchedUuids.contains(it) }
                .also { log.debug("Attempting to fetch an additional ${it.count()} scopes from chain") }
                .mapNotNull {
                    try {
                        provenanceGrpcService.retrieveScope(it)
                    } catch (e: Exception) {
                        null
                    }
                }
                .contractScopesToScopeWrappers()

            log.debug("Fetched additional ${chainScopes.scopesCount} scopes from chain")

            allScopes = allScopes.toBuilder()
                .addAllScopes(chainScopes.scopesList)
                .build()
        }

        allScopes.complete(responseObserver)
    }

    override fun queryCount(
        request: QueryRequest,
        responseObserver: StreamObserver<QueryCountResponse>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        val query = objectMapper.readValue<Query>(request.json)
        indexService.queryCount(
                publicKey(),
                query.operation
        ).toQueryCountResponse()
        .complete(responseObserver)
    }

    override fun queryWithCount(
        request: QueryRequest,
        responseObserver: StreamObserver<QueryScopeWrapper>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        val query = objectMapper.readValue<Query>(request.json)
        indexService.queryWithCount(publicKey(), query.operation, query.size, query.from)
            .toQueryScopeWrapper()
            .complete(responseObserver)
    }

    override fun getFactHistory(
        request: FactHistoryRequest,
        responseObserver: StreamObserver<FactHistoryResponse>
    ) {
        P8eMDC.set(publicKey(), clear = true)

        val signer = transaction { affiliateService.getSigner(publicKey()) }
        val encryptionKeyRef = transaction { affiliateService.getEncryptionKeyRef(publicKey()) }

        indexService.findByScopeUuid(
            request.scopeUuid.toUuidProv(),
            request.startWindow.toOffsetDateTimeProv(),
            request.endWindow.toOffsetDateTimeProv(),
            isAsc = true
        ).flatMap { scopeRecord ->
            scopeRecord.scope
                .recordGroupList
                .flatMap { recordGroup ->
                    recordGroup.recordsList
                        .filter { it.resultName == request.factName && it.classname == request.classname }
                        .map { record ->
                            FactHistoryResponseEntry.newBuilder()
                                .setExecutor(recordGroup.executor)
                                .addAllParties(recordGroup.partiesList)
                                .setContractJarHash(record.hash)
                                .setContractClassname(recordGroup.classname)
                                .setFunctionName(record.name)
                                .setResultName(record.resultName)
                                .setResultHash(record.resultHash)
                                .setFactAuditFields(recordGroup.audit)
                                .setBlockNumber(scopeRecord.blockNumber)
                                .setBlockTransactionIndex(scopeRecord.blockTransactionIndex)
                                .build()
                        }
                }
        }.removeSuccessiveDuplicatesBy { it.resultHash }
            .parmapProv(executor) { entry ->
                entry.toBuilder()
                    .setFactBytes(
                        definitionService.get(
                            encryptionKeyRef,
                            entry.resultHash,
                            entry.contractClassname
                        ).readAllBytes()
                            .toByteString()
                    ).build()
            }
            .let { entries ->
                FactHistoryResponse.newBuilder()
                    .addAllEntries(entries)
                    .build()
            }.complete(responseObserver)
    }

    companion object {
        val executor = ThreadPoolFactory.newFixedThreadPool(8, "fact-history-%d")
    }
}

fun <T, K> List<T>.removeSuccessiveDuplicatesBy(selector: (T) -> K): List<T> {
    var previousValue: K? = null
    return filter {  item ->
        (previousValue != selector(item))
            .also { previousValue = selector(item) }
    }
}

fun IndexScopeRecord.toScopeWrapper(): ScopeWrapper {
    return ScopeWrapper.newBuilder()
        .setBlockNumber(blockNumber)
        .setBlockTransactionIndex(blockTransactionIndex)
        .setScope(scope)
        .build()
}

fun List<IndexScopeRecord>.toScopeWrappers(): ScopeWrappers {
    return ScopeWrappers.newBuilder()
        .addAllScopes(map { it.toScopeWrapper() })
        .build()
}

fun ContractScope.Scope.toScopeWrapper(): ScopeWrapper = ScopeWrapper.newBuilder()
    .setScope(this)
    .build()

fun List<ContractScope.Scope>.contractScopesToScopeWrappers(): ScopeWrappers = ScopeWrappers.newBuilder()
    .addAllScopes(map { it.toScopeWrapper() })
    .build()

fun SearchResponse.toRawQueryResults() = Index.RawQueryResults.newBuilder()
    .addAllResults(hits.map { hit ->
        Index.RawQueryResult.newBuilder()
            .setId(hit.id)
            .setFieldJson(hit.sourceAsMap.toJsonString())
            .build()
    }).build()

fun io.p8e.index.domain.QueryCountResponse.toQueryCountResponse(): QueryCountResponse {
    return QueryCountResponse.newBuilder()
        .setCount(count)
        .setIsTerminateEarly(isTerminateEarly)
        .setSuccessfulShards(successfulShards)
        .setSkippedShards(skippedShards)
        .setFailedShards(failedShards)
        .setTotalShards(totalShards)
        .build()
}

fun io.p8e.index.domain.QueryCountWrapper<IndexScopeRecord>.toQueryScopeWrapper(): QueryScopeWrapper {
    return QueryScopeWrapper.newBuilder()
        .setTotalHits(totalHits)
        .addAllScopes(elements.map { it.toScopeWrapper() })
        .build()
}
