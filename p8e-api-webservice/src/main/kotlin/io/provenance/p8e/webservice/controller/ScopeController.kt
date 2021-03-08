package io.provenance.p8e.webservice.controller

import io.p8e.util.orThrowNotFound
import io.provenance.p8e.webservice.repository.ScopeRepository
import org.springframework.web.bind.annotation.*
import java.util.*

@CrossOrigin(origins = ["http://localhost:3000"], allowCredentials = "true")
@RestController
@RequestMapping("scopes")
open class ScopeController(private val scopeRepository: ScopeRepository) {
    @GetMapping()
    fun index(@RequestParam("limit", defaultValue = "100") limit: Int, @RequestParam("q") q: String?) = scopeRepository.getMany(limit, q)

    @GetMapping("{scope_uuid}")
    fun getScope(@PathVariable("scope_uuid") scopeUuid: UUID) = scopeRepository.getOne(scopeUuid)
        .orThrowNotFound("Scope not found")

    @GetMapping("{scope_uuid}/history")
    fun getScopeHistory(@PathVariable("scope_uuid") scopeUuid: UUID) = scopeRepository.getHistory(scopeUuid)

    @GetMapping("history/{uuid}")
    fun getScopeHistoryDetails(@PathVariable("uuid") id: UUID) = scopeRepository.getHistoryDetails(id)
        .orThrowNotFound("Scope history record not found")
}