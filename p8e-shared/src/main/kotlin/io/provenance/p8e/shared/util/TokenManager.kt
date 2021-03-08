package io.provenance.p8e.shared.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.p8e.proto.Authentication.Jwt
import io.p8e.util.*
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.config.JwtProperties
import org.springframework.stereotype.Component
import java.security.PublicKey
import java.time.Instant
import java.util.Date

abstract class JwtClaims(vararg val claims: JwtClaim<*>) {
    companion object {
        fun cloneFromJwt(jwt: DecodedJWT): JwtClaims {
            throw NotImplementedError("cloneFromJwt not implemented")
        }
    }
}

class KeyClaims(val publicKey: PublicKey) : JwtClaims(
    PublicKeyClaim(publicKey)
) {
    companion object {
        val cloneFromJwt: (DecodedJWT) -> KeyClaims = { jwt ->
            KeyClaims(
                PublicKeyClaim.validate(jwt.claims[PublicKeyClaim.KEY]?.asString())
            )
        }
    }
}

class IdentityClaims(val provenanceJwt: String) : JwtClaims(
    ProvenanceJwtClaim(provenanceJwt),
    ProvenanceIdentityClaim(provenanceJwt)
) {
    companion object {
        val cloneFromJwt: (DecodedJWT) -> IdentityClaims = { jwt ->
            IdentityClaims(
                ProvenanceJwtClaim.validate(jwt.claims[ProvenanceJwtClaim.KEY]?.asString())
            )
        }
    }
}

abstract class JwtClaim<T>(public val key: String, protected val value: T) {
    abstract fun getValue(): String
}

class PublicKeyClaim(private val publicKey: PublicKey) : JwtClaim<PublicKey>(
    KEY, publicKey) {
    companion object {
        const val KEY = "publicKey"
        fun validate(value: String?) = value?.toJavaPublicKey()
            .orThrow { IllegalArgumentException("Provided JWT is missing Public Key claim.") }
    }

    override fun getValue(): String = publicKey.toHex()
}

class ProvenanceJwtClaim(private val jwt: String) : JwtClaim<String>(
    KEY, jwt) {
    companion object {
        const val KEY = "provenanceJwt"
        fun validate(value: String?): String = value?.orThrow { IllegalArgumentException("Provided JWT is missing Provenance JWT claim.") }!!
    }

    override fun getValue(): String = value
}

class ProvenanceIdentityClaim(private val jwt: String) : JwtClaim<String>(KEY, extractUuid(jwt)) {
    companion object {
        const val KEY = "provenanceIdentityUuid"
        fun validate(value: String?): String = value?.toUuidProv().toString()
            .orThrow { IllegalArgumentException("Provided JWT is missing Provenance Identity UUID claim.") }

        fun extractUuid(jwt: String) = JWT.decode(jwt).claims["uuid"]?.asString().let {
            validate(it)
        }
    }

    override fun getValue(): String = value
}

class TokenManager<T : JwtClaims>(
    jwtProperties: JwtProperties,
    private val claimGenerator: (jwt: DecodedJWT) -> T
) {
    private val expireSeconds = jwtProperties.expireSeconds.toLong()

    private val consumer = jwtProperties.consumer
    private val issuer = jwtProperties.issuer
    private val algorithm = Algorithm.HMAC256(jwtProperties.secret)
    private val verifier = JWT.require(algorithm).withIssuer(issuer).build()

    fun create(
        claims: T
    ): Jwt {
        val jwt = JWT.create()
            .also {
                claims.claims.forEach { claim -> it.withClaim(claim.key, claim.getValue()) }
            }
            .withClaim(CONSUMER_CLAIM, consumer)
            .withIssuer(issuer)
            .withExpiresAt(Date.from(Instant.now().plusSeconds(expireSeconds)))
            .withIssuedAt(Date.from(Instant.now().minusSeconds(1)))
            .sign(algorithm)

        return Jwt.newBuilder()
            .setToken(jwt)
            .build()
    }

    fun verify(
        jwt: String
    ): DecodedJWT {
        return verifier.verify(jwt)
    }

    fun rotate(
        jwt: String
    ): Jwt {
        val decoded = verify(jwt)

        return create(
            claimGenerator(decoded)
        )
    }

    companion object {
        const val CONSUMER_CLAIM = "con"
    }
}
