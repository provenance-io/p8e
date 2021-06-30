package io.provenance.p8e.shared.service

import io.p8e.util.toPublicKeyProto
import io.provenance.p8e.shared.domain.JobRecord
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.PublicKey
import org.springframework.stereotype.Service
import p8e.Jobs

@Service
class DataAccessService {
    fun addDataAccess(dataAccess: Jobs.MsgAddScopeDataAccessRequest) = transaction {
        JobRecord.create(Jobs.P8eJob.newBuilder()
            .setMsgAddScopeDataAccessRequest(dataAccess)
            .build()
        )
    }
}
