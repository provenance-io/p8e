package io.provenance.engine.grpc.interceptors

import io.grpc.*
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener
import io.grpc.ServerCall.Listener
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger

val TRAILER_CLASSNAME_KEY = Metadata.Key.of("classname", Metadata.ASCII_STRING_MARSHALLER)

class UnhandledExceptionInterceptor: ServerInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): Listener<ReqT> {
        val delegate = next.startCall(call, headers)
        return ExceptionListener(call, delegate)
    }
}

class ExceptionListener<ReqT: Any, RespT: Any>(
    private val call: ServerCall<ReqT, RespT>,
    delegate: Listener<ReqT>
): SimpleForwardingServerCallListener<ReqT>(delegate) {
    override fun onHalfClose() {
        try {
            super.onHalfClose()
        } catch (t: Throwable) {
            handleException(t, call)
        }
    }

    override fun onComplete() {
        try {
            super.onComplete()
        } catch(t: Throwable) {
            handleException(t, call)
        }
    }

    override fun onCancel() {
        try {
            super.onCancel()
        } catch(t: Throwable) {
            handleException(t, call)
        }
    }

    override fun onMessage(message: ReqT) {
        try {
            super.onMessage(message)
        } catch(t: Throwable) {
            handleException(t, call)
        }
    }

    override fun onReady() {
        try {
            super.onReady()
        } catch(t: Throwable) {
            handleException(t, call)
        }
    }

    private fun handleException(
        t: Throwable,
        call: ServerCall<ReqT, RespT>
    ) {
        val (status, trailers) = status(t)

        call.close(status, trailers ?: Metadata())
    }
}

fun Throwable.statusRuntimeException(): StatusRuntimeException =
    status(this).let { (status, trailers) -> status.asRuntimeException(trailers) }

private fun status(t: Throwable): Pair<Status, Metadata?> {
    val (status, trailers) = when (t) {
        is StatusRuntimeException -> Pair(t.status, t.trailers)
        is StatusException -> Pair(t.status, t.trailers)
        else -> Pair(
            ExceptionMapper.getStatus(t).withDescription(t.message),
            Metadata().also { it.put(TRAILER_CLASSNAME_KEY, t::class.java.name) }
        )
    }

    when (t) {
        is StatusRuntimeException -> logger().error("Internal forwarded grpc runtime error", t)
        is StatusException -> logger().error("Internal forwarded grpc error", t)
        else -> {
            if (!ExceptionMapper.whiteListNonLoggable.contains(status.javaClass.name)) {
                logger().error("Internal error", t)
            }
        }
    }

    return Pair(status, trailers)
}

enum class ExceptionMapper(
    private val status: Status,
    private val exceptions: List<Class<out Throwable>>
) {
    INVALID_ARGUMENT(
        Status.INVALID_ARGUMENT,
        listOf(
            IllegalArgumentException::class.java,
            AffiliateConnectionException::class.java,
            ContractDefinitionException::class.java,
            ProtoParseException::class.java,
            ContractValidationException::class.java
        )
    ),
    NOT_FOUND(Status.NOT_FOUND, listOf(NotFoundException::class.java)),
    NOT_AUTHENTICATED(Status.UNAUTHENTICATED, listOf(AuthenticationException::class.java)),
    ILLEGAL_STATE(Status.INTERNAL, listOf(IllegalStateException::class.java));

    companion object {
        val whiteListNonLoggable = setOf(Status.INVALID_ARGUMENT, Status.NOT_FOUND, Status.UNAUTHENTICATED)
            .map { it.javaClass.name }
        val exceptionToStatus = values()
            .flatMap { statusException ->
                statusException.exceptions.map { it to statusException.status }
            }.toMap()

        fun getStatus(t: Throwable): Status {
            return exceptionToStatus[t.javaClass] ?: Status.UNKNOWN
        }
    }
}
