package io.p8e.util.feign

import feign.FeignException

class WrappedFeignException(
    status: Int,
    message: String,
    body: ByteArray
): FeignException(status, message, body)