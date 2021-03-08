package io.provenance.p8e.webservice.interceptors

import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.p8e.util.toUuidProv
import io.provenance.p8e.shared.util.IdentityClaims
import io.provenance.p8e.shared.util.ProvenanceIdentityClaim
import io.provenance.p8e.shared.util.ProvenanceJwtClaim
import io.provenance.p8e.shared.util.TokenManager
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import java.util.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Component
class JWTInterceptor(
    private val tokenManager: TokenManager<IdentityClaims>
) : HandlerInterceptor {
    companion object {
        const val PROVENANCE_JWT_KEY = "JWT"
        const val PROVENANCE_IDENTITY_UUID_KEY = "PROVENANCE_IDENTITY_UUID"
        const val JWT_HEADER_KEY = "Authorization"
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method == "OPTIONS") {
            return true
        }

        val jwtString = request.getHeader(JWT_HEADER_KEY)?.removePrefix("Bearer")?.trimStart()
        if (jwtString == null) {
            response.sendError(401)
            return false
        }

        lateinit var jwt: DecodedJWT
        try {
            jwt = tokenManager.verify(jwtString)
        } catch (ex: JWTVerificationException) {
            response.sendError(401)
            return false
        }
        val provenanceJwt = jwt.claims[ProvenanceJwtClaim.KEY]?.asString()
        val provenanceIdentityUuid = jwt.claims[ProvenanceIdentityClaim.KEY]?.asString()

        if (provenanceJwt == null || provenanceIdentityUuid == null) {
            response.sendError(400, "JWT Token is missing Provenance JWT claims")
            return false
        }

        if ((Date().time + 60000) > jwt.expiresAt.time) {
            response.setHeader(JWT_HEADER_KEY, tokenManager.rotate(jwtString).token)
        }

        RequestContextHolder.currentRequestAttributes().setAttribute(PROVENANCE_JWT_KEY, provenanceJwt, RequestAttributes.SCOPE_REQUEST)
        RequestContextHolder.currentRequestAttributes().setAttribute(PROVENANCE_IDENTITY_UUID_KEY, provenanceIdentityUuid.toUuidProv(), RequestAttributes.SCOPE_REQUEST)

        return true;
    }
}

fun provenanceJwt() = RequestContextHolder.currentRequestAttributes().getAttribute(JWTInterceptor.PROVENANCE_JWT_KEY, RequestAttributes.SCOPE_REQUEST) as String
fun provenanceIdentityUuid() = RequestContextHolder.currentRequestAttributes().getAttribute(JWTInterceptor.PROVENANCE_IDENTITY_UUID_KEY, RequestAttributes.SCOPE_REQUEST) as UUID
