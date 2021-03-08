package io.provenance.p8e.webservice.service

import io.provenance.p8e.shared.util.TokenManager
import io.provenance.p8e.shared.util.IdentityClaims
import org.springframework.stereotype.Component

@Component
class AuthenticationService(
    private val tokenManager: TokenManager<IdentityClaims>
) {
    fun authenticate(
        provenanceJwt: String
    ) = tokenManager.create(IdentityClaims(provenanceJwt))
}