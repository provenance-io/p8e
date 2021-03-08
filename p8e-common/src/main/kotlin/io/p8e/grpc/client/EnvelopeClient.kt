package io.p8e.grpc.client

import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.EnvelopeCollection
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.proto.Envelope.RejectCancel
import io.p8e.proto.EnvelopeServiceGrpc
import io.p8e.util.toProtoUuidProv
import java.util.UUID

class EnvelopeClient(
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor
) {
    private val blockingClient = EnvelopeServiceGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)

    private val eventClient = EnvelopeServiceGrpc.newStub(channel)
        .withInterceptors(interceptor)

    fun getAllByGroupUuid(
        groupUuid: UUID
    ): EnvelopeCollection {
        return blockingClient.getAllByGroupUuid(groupUuid.toProtoUuidProv())
    }

    fun getByExecutionUuid(
        executionUuid: UUID
    ): Envelope {
        return blockingClient.getByExecutionUuid(executionUuid.toProtoUuidProv())
    }

    fun getScopeByExecutionUuid(
        executionUuid: UUID
    ): Scope {
        return blockingClient.getScopeByExecutionUuid(executionUuid.toProtoUuidProv())
    }

    fun rejectByExecutionUuid(
        executionUuid: UUID,
        message: String
    ): Envelope {
        return blockingClient.rejectByExecutionUuid(
            RejectCancel.newBuilder()
                .setExecutionUuid(executionUuid.toProtoUuidProv())
                .setMessage(message)
                .build()
        )
    }

    fun cancelByExecutionUuid(
        executionUuid: UUID,
        message: String
    ): Envelope {
        return blockingClient.cancelByExecutionUuid(
            RejectCancel.newBuilder()
                .setExecutionUuid(executionUuid.toProtoUuidProv())
                .setMessage(message)
                .build())
    }

    fun event(
        inObserver: StreamObserver<EnvelopeEvent>
    ): StreamObserver<EnvelopeEvent> {
        return eventClient.event(inObserver)
    }

    fun execute(
        request: EnvelopeEvent
    ): EnvelopeEvent {
        return GrpcRetry.unavailableBackoff {
            blockingClient.execute(request)
        }
    }
}
