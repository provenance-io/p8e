package io.provenance.engine.service

import io.p8e.grpc.Constant
import io.p8e.proto.Authentication
import io.p8e.util.toHex
import io.p8e.util.toJavaPublicKey
import io.p8e.util.toPublicKey
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toOffsetDateTimeProv
import io.provenance.p8e.shared.util.KeyClaims
import io.provenance.p8e.shared.util.TokenManager
import io.provenance.p8e.shared.service.AffiliateService
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.security.Signature
import java.time.OffsetDateTime

@Component
class AuthenticationService(
    private val tokenManager: TokenManager<KeyClaims>,
    private val affiliateService: AffiliateService
) {
    private val log = logger()

    fun authenticate(
        request: Authentication.AuthenticationRequest
    ): Authentication.Jwt? {
        val publicKey = request.publicKey.toPublicKey()

        val affiliate = transaction { affiliateService.get(publicKey) }

        if (affiliate == null) {
            log.debug("Affiliate does not exist")
            return null
        }

        if (!affiliate.active) {
            log.info("Affiliate blocked due to inactive flag")
            return null
        }

        // Verify that the token is not expired
        val token = Authentication.AuthenticationToken.parseFrom(request.token.toByteArray())

        val expirationTime = token.expiration.toOffsetDateTimeProv()
        if (expirationTime.isBefore(OffsetDateTime.now())) {
            log.info("Token expired at $expirationTime")
            return null
        } else if (expirationTime.isAfter(OffsetDateTime.now().plusMinutes(1))) {
            log.info("Token expiration of $expirationTime is too far in the future")
            return null
        }

        // Verify signature against the auth public key we have on record.
        val verified = Signature.getInstance(Constant.JWT_ALGORITHM).apply {
            initVerify(affiliate.authPublicKey.toJavaPublicKey())
            update(request.token.toByteArray())
        }.verify(request.signature.toByteArray())

        return if (verified)
            tokenManager.create(KeyClaims(publicKey))
        else
            null
    }
}
