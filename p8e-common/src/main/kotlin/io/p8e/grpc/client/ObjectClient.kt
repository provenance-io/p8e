package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.p8e.proto.Common.Location
import io.p8e.proto.Common.WithAudience
import io.p8e.proto.ObjectGrpc
import io.p8e.proto.Objects
import io.p8e.proto.Objects.ObjectLoadRequest
import java.util.concurrent.TimeUnit

class ObjectClient(
    channel: ManagedChannel,
    challengeResponseInterceptor: ChallengeResponseInterceptor,
    deadlineMs: Long
) {
    private val client = ObjectGrpc.newBlockingStub(channel)
        .withInterceptors(challengeResponseInterceptor)
        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

    fun store(
        withAudience: WithAudience
    ): Location {
        return client.store(withAudience)
    }

    fun load(
        uri: String
    ): ByteArray {
        return client.load(
            ObjectLoadRequest.newBuilder()
                .setUri(uri)
                .build()
        ).bytes.toByteArray()
    }

    fun loadJson(
        hash: String,
        className: String,
        contractSpecHash: String
    ): String {
        return client.loadJson(
            Objects.ObjectLoadJsonRequest.newBuilder()
                .setHash(hash)
                .setClassname(className)
                .setContractSpecHash(contractSpecHash)
                .build()
        ).json
    }
}