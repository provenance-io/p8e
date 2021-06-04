package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.p8e.proto.Authentication
import io.p8e.proto.Authentication.Jwt
import io.p8e.proto.AuthenticationServiceGrpc
import java.util.concurrent.TimeUnit

class AuthenticationClient(
    channel: ManagedChannel,
    deadlineMs: Long
) {
    private val blockingStub = AuthenticationServiceGrpc
        .newBlockingStub(channel)
        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

    fun authenticate(
        request: Authentication.AuthenticationRequest
    ): Jwt {
        return blockingStub.authenticate(request)
    }
}