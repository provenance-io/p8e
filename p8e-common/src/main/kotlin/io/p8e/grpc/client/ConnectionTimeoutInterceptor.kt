/**
 * Based on https://github.com/carl-mastrangelo/grpc-java/commit/4a31a589c98f5f9c6f87206a6cb3c8d43b136924
 * Referenced in this issue https://github.com/grpc/grpc-java/issues/5498
 */

package io.p8e.grpc.client

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.p8e.util.ThreadPoolFactory
import io.provenance.p8e.shared.extension.logger
import java.time.Duration
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit


class ConnectionTimeoutInterceptor(
    private val timeout: Duration
) : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>?, callOptions: CallOptions?, next: Channel
    ): ClientCall<ReqT, RespT> {
        return ConnectionTimeoutManager(next.newCall(method, callOptions), timeout)
    }

    private class ConnectionTimeoutManager<ReqT, RespT>(delegate: ClientCall<ReqT, RespT>?, timeout: Duration) : SimpleForwardingClientCall<ReqT, RespT>(delegate) {

        private val timeoutFuture: ScheduledFuture<*>
        init {
            timeoutFuture = TIMER.schedule({ cancel("Connection Timeout", null) }, timeout.toMillis(), TimeUnit.MILLISECONDS)
        }

        override fun start(listener: Listener<RespT>?, headers: io.grpc.Metadata?) {
            super.start(object : SimpleForwardingClientCallListener<RespT>(listener) {
                override fun onHeaders(headers: Metadata?) {
                    timeoutFuture.cancel(false)
                    super.onHeaders(headers)
                }
            }, headers)
        }
    }

    companion object {
        private val TIMER: ScheduledExecutorService = ThreadPoolFactory.newScheduledThreadPool(1, "connection-timeout-%d")
    }
}
