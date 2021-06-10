package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.p8e.proto.Affiliate
import io.p8e.proto.AffiliateServiceGrpc
import java.util.concurrent.TimeUnit

class SigningAndEncryptionPublicKeysClient (
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor,
    private val deadlineMs: Long
) {
    private val client = AffiliateServiceGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)

    fun register(
        request: Affiliate.AffiliateRegisterRequest
    ) {
        client.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .register(request)
    }

    fun whitelistClass(
        whitelist: Affiliate.AffiliateContractWhitelist
    ) {
        client.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .whitelistClass(whitelist)
    }
}