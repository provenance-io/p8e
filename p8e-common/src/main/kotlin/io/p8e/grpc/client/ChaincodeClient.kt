package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.p8e.proto.ChaincodeGrpc
import io.p8e.proto.Domain.SpecRequest
import java.util.concurrent.TimeUnit

class ChaincodeClient(
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor,
    deadlineMs: Long
) {
    private val client = ChaincodeGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)
        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

    fun addSpec(
        specRequest: SpecRequest
    ) {
        client.addSpec(specRequest)
    }
}