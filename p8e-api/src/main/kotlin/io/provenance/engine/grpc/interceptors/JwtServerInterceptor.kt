package io.provenance.engine.grpc.interceptors

import io.grpc.*
import io.grpc.ServerCall.Listener
import io.p8e.grpc.Constant
import io.provenance.p8e.shared.util.KeyClaims
import io.provenance.p8e.shared.util.PublicKeyClaim
import io.provenance.p8e.shared.util.TokenManager

class JwtServerInterceptor(
    private val tokenManager: TokenManager<KeyClaims>
): ServerInterceptor {

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): Listener<ReqT> {
        val jwtString = headers[Constant.JWT_METADATA_KEY]
        if (jwtString == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("JWT Token is missing from Metadata."), headers)
            return object: ServerCall.Listener<ReqT>() {}
        }

        val jwt = tokenManager.verify(jwtString)
        val publicKey = jwt.claims[PublicKeyClaim.KEY]?.asString()

        if (publicKey == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("JWT Token is missing Public Key claims."), headers)
            return object: ServerCall.Listener<ReqT>() {}
        }

        val context = Context.current()
            .withValue(Constant.PUBLIC_KEY_CTX, publicKey)

        return Contexts.interceptCall(context, call, headers, next)
    }
}
