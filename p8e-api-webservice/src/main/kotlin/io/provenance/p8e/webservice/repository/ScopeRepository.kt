package io.provenance.p8e.webservice.repository

import io.provenance.p8e.shared.domain.ScopeRecord
import io.provenance.p8e.shared.index.data.IndexScopeRecord
import io.provenance.p8e.webservice.domain.ApiIndexScope
import io.provenance.p8e.webservice.domain.ApiScope
import io.provenance.p8e.webservice.domain.toApi
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.util.*

@Component
class ScopeRepository {
    fun getMany(limit: Int, q: String?): List<ApiScope> = transaction {
        ScopeRecord.search(limit, q).map { it.toApi() }
    }

    fun getOne(scopeUuid: UUID): ApiScope? = transaction {
        ScopeRecord.findByScopeUuid(scopeUuid)
            ?.toApi(true)
    }

    fun getHistory(scopeUuid: UUID): List<ApiIndexScope> = transaction {
        IndexScopeRecord.findByScopeUuid(scopeUuid, isAsc = true, includeData = false)
            .map { it.toApi() }
    }

    fun getHistoryDetails(id: UUID): ApiIndexScope? = transaction {
        IndexScopeRecord.findById(id)?.toApi(true)
    }
}