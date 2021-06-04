package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.p8e.proto.Authentication
import io.p8e.proto.Authentication.Jwt
import io.p8e.proto.AuthenticationServiceGrpc
import java.util.concurrent.TimeUnit

class AuthenticationClient(
    channel: ManagedChannel,
    private val deadlineMs: Long
) {
    private val blockingStub = AuthenticationServiceGrpc
        .newBlockingStub(channel)

    fun authenticate(
        request: Authentication.AuthenticationRequest
    ): Jwt {
        return blockingStub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .authenticate(request)
    }
}