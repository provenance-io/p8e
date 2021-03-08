package io.p8e.util.feign

import feign.FeignException
import feign.FeignException.BadGateway
import feign.FeignException.BadRequest
import feign.FeignException.Conflict
import feign.FeignException.Forbidden
import feign.FeignException.GatewayTimeout
import feign.FeignException.Gone
import feign.FeignException.InternalServerError
import feign.FeignException.MethodNotAllowed
import feign.FeignException.NotAcceptable
import feign.FeignException.NotFound
import feign.FeignException.NotImplemented
import feign.FeignException.ServiceUnavailable
import feign.FeignException.TooManyRequests
import feign.FeignException.Unauthorized
import feign.FeignException.UnprocessableEntity
import feign.FeignException.UnsupportedMediaType
import feign.Response
import feign.RetryableException
import feign.codec.ErrorDecoder
import java.lang.Exception

class BodyErrorDecoder: ErrorDecoder {
    private val retryAfterDecoder = RetryAfterDecoder()

    override fun decode(
        methodKey: String,
        response: Response
    ): Exception {
        val body = response.body()
            .asInputStream()
            .readAllBytes()
            .toString(Charsets.UTF_8)

        val exception = WrappedFeignException(
            response.status(),
            "status ${response.status()} reading $methodKey\n message: $body",
            body.toByteArray()
        )

        val retryAfter = retryAfterDecoder.apply(
            response.headers()[RETRY_HEADER]?.firstOrNull()
        )

        return if (retryAfter != null) {
            RetryableException(
                exception.status(),
                exception.message,
                response.request().httpMethod(),
                exception,
                retryAfter
            )
        } else exception
    }

    companion object {
        val RETRY_HEADER = "Retry-After"

        private fun errorStatus(status: Int, message: String, body: ByteArray): FeignException {
            when (status) {
                400 -> return BadRequest(
                    message,
                    body
                )
                401 -> return Unauthorized(
                    message,
                    body
                )
                403 -> return Forbidden(
                    message,
                    body
                )
                404 -> return NotFound(
                    message,
                    body
                )
                405 -> return MethodNotAllowed(
                    message,
                    body
                )
                406 -> return NotAcceptable(
                    message,
                    body
                )
                409 -> return Conflict(
                    message,
                    body
                )
                410 -> return Gone(
                    message,
                    body
                )
                415 -> return UnsupportedMediaType(
                    message,
                    body
                )
                429 -> return TooManyRequests(
                    message,
                    body
                )
                422 -> return UnprocessableEntity(
                    message,
                    body
                )
                500 -> return InternalServerError(
                    message,
                    body
                )
                501 -> return NotImplemented(
                    message,
                    body
                )
                502 -> return BadGateway(
                    message,
                    body
                )
                503 -> return ServiceUnavailable(
                    message,
                    body
                )
                504 -> return GatewayTimeout(
                    message,
                    body
                )
                else -> return WrappedFeignException(
                    status,
                    message,
                    body
                )
            }
        }
    }
}