package io.provenance.engine.grpc.v1

import io.grpc.stub.StreamObserver
import io.p8e.proto.Authentication
import io.p8e.proto.Authentication.Jwt
import io.p8e.proto.AuthenticationServiceGrpc.AuthenticationServiceImplBase
import io.p8e.util.AuthenticationException
import io.p8e.util.toHex
import io.p8e.util.toPublicKey
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.engine.service.AuthenticationService
import io.provenance.p8e.shared.util.P8eMDC
import org.lognet.springboot.grpc.GRpcService

@GRpcService(interceptors = [UnhandledExceptionInterceptor::class])
class AuthenticationGrpc(
    private val authenticationService: AuthenticationService
): AuthenticationServiceImplBase() {

    override fun authenticate(
        request: Authentication.AuthenticationRequest,
        responseObserver: StreamObserver<Jwt>
    ) {
        P8eMDC.set(request.publicKey.toPublicKey(), clear = true)

        val jwt = authenticationService.authenticate(request)
            ?: throw AuthenticationException("Authentication failed for public key: ${request.publicKey.toHex()}")

        responseObserver.onNext(jwt)
        responseObserver.onCompleted()
    }
}
