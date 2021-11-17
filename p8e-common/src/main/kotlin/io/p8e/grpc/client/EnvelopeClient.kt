package io.p8e.grpc.client

import io.grpc.ManagedChannel
import io.grpc.stub.StreamObserver
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.EnvelopeCollection
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.proto.Envelope.RejectCancel
import io.p8e.proto.EnvelopeServiceGrpc
import io.p8e.util.toProtoUuidProv
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class EnvelopeClient(
    channel: ManagedChannel,
    interceptor: ChallengeResponseInterceptor,
    private val deadlineMs: Long
) {
    private val connectionTimeoutInterceptor = ConnectionTimeoutInterceptor(Duration.ofMillis(deadlineMs))

    private val blockingClient = EnvelopeServiceGrpc.newBlockingStub(channel)
        .withInterceptors(interceptor)

    private val eventClient = EnvelopeServiceGrpc.newStub(channel)
        .withInterceptors(interceptor)

    fun getAllByGroupUuid(
        groupUuid: UUID
    ): EnvelopeCollection {
        return blockingClient.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .getAllByGroupUuid(groupUuid.toProtoUuidProv())
    }

    fun getByExecutionUuid(
        executionUuid: UUID
    ): Envelope {
        return blockingClient.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .getByExecutionUuid(executionUuid.toProtoUuidProv())
    }

    fun getScopeByExecutionUuid(
        executionUuid: UUID
    ): Scope {
        return blockingClient.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .getScopeByExecutionUuid(executionUuid.toProtoUuidProv())
    }

    fun rejectByExecutionUuid(
        executionUuid: UUID,
        message: String
    ): Envelope {
        return blockingClient.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .rejectByExecutionUuid(
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
        return blockingClient.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
            .cancelByExecutionUuid(
                RejectCancel.newBuilder()
                    .setExecutionUuid(executionUuid.toProtoUuidProv())
                    .setMessage(message)
                    .build()
            )
    }

    fun event(
        inObserver: StreamObserver<EnvelopeEvent>
    ): StreamObserver<EnvelopeEvent> {
        return eventClient.withInterceptors(connectionTimeoutInterceptor)
            .event(inObserver)
    }

    fun execute(
        request: EnvelopeEvent
    ): EnvelopeEvent {
        return GrpcRetry.unavailableBackoff {
            blockingClient.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .execute(request)
        }
    }
}
