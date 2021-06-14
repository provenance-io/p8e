package io.provenance.p8e.webservice.controller

import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.*
import java.security.KeyPair
import io.p8e.util.*
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.p8e.shared.domain.ServiceAccountRecord
import io.provenance.p8e.shared.domain.ServiceAccountStates
import io.provenance.p8e.shared.service.ServiceAccountService
import io.provenance.p8e.webservice.domain.ApiPublicKey

data class ApiServiceKey(
    val publicKey: ApiPublicKey,
    val status: String,
    val alias: String?
)

fun ServiceAccountRecord.toApi(): ApiServiceKey {
    val publicKey = this.publicKey.value.toJavaPublicKey()
    return ApiServiceKey(
        ApiPublicKey(publicKey.toHex()),
        status,
        alias
    )
}

data class UpdateServiceKey(val alias: String)

data class RegisterServiceKey(val privateKey: String?, val alias: String?) {
    var keyPair: KeyPair = privateKey?.takeIf { it.isNotBlank() }?.toJavaPrivateKey()?.let {
        KeyPair(it.computePublicKey(), it)
    } ?: ProvenanceKeyGenerator.generateKeyPair()
}

// todo: remove CrossOrigin and let Kong handle this
@CrossOrigin(origins = ["http://localhost:3000"], allowCredentials = "true")
@RestController
@RequestMapping("keys/service")
open class ServiceAccountController(val serviceAccountService: ServiceAccountService) {
    @GetMapping("")
    fun index() = transaction { serviceAccountService.getAll() }.map{ it.toApi() }

    @PatchMapping("{publicKey}")
    fun update(@PathVariable("publicKey") publicKey: String, @RequestBody body: UpdateServiceKey) = transaction { serviceAccountService.update(
        publicKey.toJavaPublicKey(),
        alias = body.alias
    ) }?.toApi()

    @PostMapping("")
    fun add(@RequestBody body: RegisterServiceKey) = transaction {
        serviceAccountService.save(body.keyPair, ServiceAccountStates.INITIALIZED, body.alias)
    }.toApi()
}
