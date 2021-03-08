package io.p8e.functional

import io.p8e.proto.ContractScope.EnvelopeError

@FunctionalInterface
interface ContractErrorHandler {
    fun handle(error: EnvelopeError): Boolean
}