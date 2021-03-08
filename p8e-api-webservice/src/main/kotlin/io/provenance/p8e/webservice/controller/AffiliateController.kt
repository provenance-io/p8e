package io.provenance.p8e.webservice.controller

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.*
import io.p8e.util.*
import io.provenance.p8e.webservice.domain.*
import io.provenance.p8e.webservice.repository.AffiliateRepository

// todo: remove CrossOrigin and let Kong handle this
@CrossOrigin(origins = ["http://localhost:3000"], allowCredentials = "true")
@RestController
@RequestMapping("keys/affiliate")
open class AffiliateController(private val affiliateRepository: AffiliateRepository) {
    @GetMapping("")
    fun index() = affiliateRepository.getAll()

    @PatchMapping("{publicKey}")
    fun update(@PathVariable("publicKey") publicKey: String, @RequestBody body: UpdateAffiliateKey) = publicKey.toJavaPublicKey().let {
        affiliateRepository.update(it, body.alias)
    }

    @PostMapping("")
    fun add(@RequestBody body: RegisterAffiliateKey) = affiliateRepository
        .create(body.signingKeyPair, body.encryptionKeyPair, body.indexName, body.alias)

    @GetMapping("{affiliatePublicKey}/shares")
    fun getShares(@PathVariable("affiliatePublicKey") affiliatePublicKey: String) = affiliatePublicKey.toJavaPublicKey()
        .let { affiliateRepository.getShares(it) }

    @PostMapping("{affiliatePublicKey}/shares")
    fun addShare(@PathVariable("affiliatePublicKey") affiliatePublicKey: String, @RequestBody body: AddShare) = (affiliatePublicKey.toJavaPublicKey() to body.publicKey.toJavaPublicKey())
        .let {(affiliatePublicKey, sharePublicKey) ->
            affiliateRepository.addShare(affiliatePublicKey, sharePublicKey)
        }

    @DeleteMapping("{affiliatePublicKey}/shares/{publicKey}")
    fun removeShare(@PathVariable("affiliatePublicKey") affiliatePublicKey: String, @PathVariable("publicKey") publicKey: String) = (affiliatePublicKey.toJavaPublicKey() to publicKey.toJavaPublicKey())
        .let { (affiliatePublicKey, sharePublicKey) ->
            affiliateRepository.removeShare(affiliatePublicKey, sharePublicKey).let {
                "Successfully removed public key share"
            }
        }

    @PostMapping("{affiliatePublicKey}/service_keys")
    fun attachServiceKeys(@PathVariable("affiliatePublicKey") affiliatePublicKey: String, @RequestBody body: AttachServiceKeys) = affiliatePublicKey.toJavaPublicKey().let {
        affiliateRepository.attachServiceKeys(it, body.servicePublicKeys)
    }

    @DeleteMapping("{affiliatePublicKey}/service_keys")
    fun removeServiceKeys(@PathVariable("affiliatePublicKey") affiliatePublicKey: String, @RequestBody body: AttachServiceKeys) = affiliatePublicKey.toJavaPublicKey().let {
        affiliateRepository.removeServiceKeys(it, body.servicePublicKeys)
            .let { numKeysUnlinked -> "$numKeysUnlinked service keys unlinked" }
    }
}
