package io.provenance.engine.stream.domain

import io.grpc.stub.StreamObserver
import java.util.concurrent.CountDownLatch

class EventStreamResponseObserver<T>(private val onNextHandler: (T) -> Unit) : StreamObserver<T> {
    val finishLatch: CountDownLatch = CountDownLatch(1)
    var error: Throwable? = null

    override fun onNext(value: T) {
        try {
            onNextHandler(value)
        } catch (t: Throwable) {
            this.onError(t)
        }
    }

    override fun onCompleted() {
        finishLatch.countDown()
    }

    override fun onError(t: Throwable?) {
        error = t
        finishLatch.countDown()
    }
}
