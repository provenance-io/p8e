package io.provenance.p8e.webservice.controller

import io.p8e.util.orThrowNotFound
import io.provenance.p8e.webservice.repository.EnvelopeRepository
import org.springframework.web.bind.annotation.*
import java.util.*

// todo: remove CrossOrigin and let Kong handle this
@CrossOrigin(origins = ["http://localhost:3000"], allowCredentials = "true")
@RestController
@RequestMapping("envelope")
open class EnvelopeController(private val envelopeRepository: EnvelopeRepository) {
    @GetMapping()
    fun index(@RequestParam("limit", defaultValue = "100") limit: Int,
              @RequestParam("q") q: String?,
              @RequestParam("publicKey") publicKey: String?,
              @RequestParam("type") type: String?
    ) = envelopeRepository.getMany(limit, q, publicKey, type)

    @GetMapping("{id}")
    fun getEnvelope(@PathVariable("id") id: UUID) = envelopeRepository.getOneById(id)
        .orThrowNotFound("Envelope not found")
}
