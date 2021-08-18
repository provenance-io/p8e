package io.provenance.p8e.shared.service

import io.p8e.util.toPublicKeyProto
import io.provenance.p8e.shared.domain.JobRecord
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import p8e.Jobs
import java.security.PublicKey

@Service
class OSLocatorService {
    fun registerAffiliate(publicKey: PublicKey) = transaction {
        JobRecord.create(Jobs.P8eJob.newBuilder()
            .setAddAffiliateOSLocator(Jobs.AddAffiliateOSLocator.newBuilder()
                .setPublicKey(publicKey.toPublicKeyProto())
            )
            .build())
    }
}