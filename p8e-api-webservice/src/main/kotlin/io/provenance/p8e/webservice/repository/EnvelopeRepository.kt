package io.provenance.p8e.webservice.repository

import io.provenance.p8e.shared.domain.EnvelopeRecord
import io.provenance.p8e.webservice.domain.ApiEnvelope
import io.provenance.p8e.webservice.domain.toApi
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.util.*

@Component
class EnvelopeRepository {
    fun getMany(limit: Int, q: String?, publicKey: String?, type: String?): List<ApiEnvelope> = transaction {
        EnvelopeRecord
            .search(limit, q, publicKey, type, false)
            .map { it.toApi() }
    }

    fun getOneById(id: UUID): ApiEnvelope? = transaction {
        EnvelopeRecord.findById(id)?.toApi(true)
    }
}
