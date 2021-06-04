package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.p8e.proto.Affiliate
import io.p8e.proto.AffiliateServiceGrpc
import java.util.concurrent.TimeUnit

class SigningAndEncryptionPublicKeysClient (
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor,
    deadlineMs: Long
) {
    private val client = AffiliateServiceGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)
        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

    fun register(
        request: Affiliate.AffiliateRegisterRequest
    ) {
        client.register(request)
    }

    fun whitelistClass(
        whitelist: Affiliate.AffiliateContractWhitelist
    ) {
        client.whitelistClass(whitelist)
    }
}