package io.provenance.p8e.webservice.controller

import feign.Headers
import io.p8e.ContractManager
import io.p8e.util.orThrowNotFound
import io.p8e.util.toJavaPublicKey
import io.provenance.p8e.shared.domain.AffiliateRecord
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.webservice.config.ErrorMessage
import io.provenance.p8e.webservice.config.ServiceProperties
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@CrossOrigin(origins = ["http://localhost:3000"], allowCredentials = "true")
@RestController
@RequestMapping("object")
open class ObjectController(private val affiliateService: AffiliateService, private val serviceProperties: ServiceProperties) {
    @GetMapping()
    fun getJson(
        @RequestParam("hash") hash: String,
        @RequestParam("className") className: String,
        @RequestParam("contractSpecHash") contractSpecHash: String,
        @RequestParam("publicKey") publicKey: String
    ): Any {
        if (!serviceProperties.objectFetchEnabled) {
            return ResponseEntity(ErrorMessage(listOf("Object fetching disabled")), HttpStatus.FORBIDDEN)
        }

        val affiliate = transaction { affiliateService.get(publicKey.toJavaPublicKey()) }

        requireNotNull(affiliate) { "Affiliate not found with public key $publicKey" }

        // todo: figure out how to properly authenticate to fetch object json now
        return ContractManager.create(affiliate.privateKey!!).let { cm ->
            cm.client.loadProtoJson(hash, className, contractSpecHash)
        }
    }
}
