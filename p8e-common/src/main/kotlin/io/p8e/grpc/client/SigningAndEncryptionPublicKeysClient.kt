package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.p8e.proto.Affiliate
import io.p8e.proto.AffiliateServiceGrpc
import io.p8e.proto.PK

class SigningAndEncryptionPublicKeysClient (
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor
) {
    private val client = AffiliateServiceGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)

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