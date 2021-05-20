package io.provenance.p8e.webservice.controller

import feign.Headers
import io.p8e.ContractManager
import io.p8e.util.orThrowNotFound
import io.p8e.util.toJavaPublicKey
import io.provenance.p8e.shared.domain.AffiliateRecord
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.webservice.interceptors.provenanceIdentityUuid
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.*
import java.util.*

@CrossOrigin(origins = ["http://localhost:3000"], allowCredentials = "true")
@RestController
@RequestMapping("object")
open class ObjectController(private val affiliateService: AffiliateService) {
    @GetMapping()
    fun getJson(
        @RequestParam("hash") hash: String,
        @RequestParam("className") className: String,
        @RequestParam("contractSpecHash") contractSpecHash: String,
        @RequestParam("publicKey") publicKey: String
    ): Any {
        val affiliate = transaction { affiliateService.getAffiliateByPublicKeyAndIdentityUuid(publicKey.toJavaPublicKey(), provenanceIdentityUuid()) }

        requireNotNull(affiliate) { "Identity ${provenanceIdentityUuid()} is unable to fetch objects for public key $publicKey" }

        // todo: figure out how to properly authenticate in order to fetch proto json now
        return ContractManager.create(affiliate.privateKey!!).let { cm ->
            cm.client.loadProtoJson(hash, className, contractSpecHash)
        }
    }
}
