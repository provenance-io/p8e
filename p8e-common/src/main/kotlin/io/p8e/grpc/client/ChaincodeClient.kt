package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.p8e.proto.ChaincodeGrpc
import io.p8e.proto.Domain.SpecRequest

class ChaincodeClient(
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor
) {
    private val client = ChaincodeGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)

    fun addSpec(
        specRequest: SpecRequest
    ) {
        client.addSpec(specRequest)
    }
}