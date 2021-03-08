package io.p8e.grpc.client

import com.auth0.jwt.JWT
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.p8e.grpc.Constant
import io.p8e.proto.Authentication
import io.p8e.util.toPublicKeyProto
import io.p8e.util.toByteString
import io.p8e.util.toProtoTimestampProv
import java.security.KeyPair
import java.security.Signature
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class ChallengeResponseInterceptor(
    private val keyPair: KeyPair,
    private val authenticationClient: AuthenticationClient,
    private val toleranceSeconds: Long = 3
): ClientInterceptor {
    private val jwt = AtomicReference("")

    override fun <ReqT : Any, RespT : Any> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel
    ): ClientCall<ReqT, RespT> {
        return object: ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(
                responseListener: Listener<RespT>,
                headers: Metadata
            ) {
                if (Constant.JWT_CTX_KEY.get() == null) {
                    headers.put(
                        Constant.JWT_METADATA_KEY,
                        jwt.get()
                            .takeIf { it.isNotEmpty() && !it.isExpired() }
                        ?: authenticate())
                }
                super.start(
                    responseListener,
                    headers
                )
            }
        }
    }

    private fun authenticate(): String {
        // Random String
        val randomStr = UUID.randomUUID().toString() + System.currentTimeMillis()

        // Generate a token that is value for 10 seconds
        val token = Authentication.AuthenticationToken.newBuilder()
            .setRandomData(randomStr.toByteString())
            .setExpiration(OffsetDateTime.now().plusSeconds(10).toProtoTimestampProv())
            .build()

        // Sign the random token with our private key
        val signature = Signature.getInstance(Constant.JWT_ALGORITHM).apply {
            initSign(keyPair.private)
            update(token.toByteArray())
        }.let {
            it.sign()
        }

        // Authenticate to the server using the 10 second token.
        val jwtResponse = authenticationClient.authenticate(
            Authentication.AuthenticationRequest.newBuilder()
                .setPublicKey(keyPair.public.toPublicKeyProto())
                .setSignature(signature.toByteString())
                .setToken(token)
                .build()
        )

        return jwtResponse.token.also(jwt::set)
    }

    private fun String.isExpired(): Boolean {
        return JWT.decode(this)
            .expiresAt
            .toInstant()
            .atOffset(ZoneOffset.UTC)
            .minusSeconds(toleranceSeconds)    // Give some tolerance
            .isBefore(OffsetDateTime.now())
    }
}
