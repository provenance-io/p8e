package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.p8e.proto.ChaincodeGrpc
import io.p8e.proto.Domain.SpecRequest
import java.util.concurrent.TimeUnit

class ChaincodeClient(
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor,
    private val deadlineMs: Long
) {
    private val client = ChaincodeGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)

    fun addSpec(
        specRequest: SpecRequest
    ) {
        client.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .addSpec(specRequest)
    }
}