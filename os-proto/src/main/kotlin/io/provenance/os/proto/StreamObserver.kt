package io.provenance.os.proto

import com.google.protobuf.Message
import io.grpc.stub.StreamObserver
import java.util.LinkedList

class BufferedStreamObserver<T: Message>(
    private val errorHandler: (Throwable) -> Unit,
    private val completedHandler: (LinkedList<T>, Long) -> Unit
): StreamObserver<T> {

    private val startTime = System.currentTimeMillis()
    private val buffer = LinkedList<T>()

    override fun onNext(value: T) {
        buffer.offer(value)
    }

    override fun onError(t: Throwable) = errorHandler(t)

    override fun onCompleted() = completedHandler(buffer, startTime)
}
