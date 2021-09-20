package io.p8e.exception

import io.grpc.StatusException
import io.grpc.Metadata
import io.grpc.StatusRuntimeException
import io.p8e.proto.ContractScope
import io.p8e.proto.Contracts.Contract
import io.p8e.util.*

sealed class P8eError {

    data class AffiliateConnection(val message: String) : P8eError()
    data class Authentication(val message: String) : P8eError()
    data class ContractDefinition(val message: String) : P8eError()
    data class ContractValidation(val message: String) : P8eError()
    data class ProtoParse(val message: String) : P8eError()
    data class NotFound(val message: String) : P8eError()
    data class ExecutionError(val envelopeError: ContractScope.EnvelopeError) : P8eError()
    data class PreExecutionError(val t: Throwable, val isFragment: Boolean, val contract: Contract, val envelope: ContractScope.Envelope) : P8eError()
    data class Unknown(val t: Throwable) : P8eError()

    companion object {
        val TRAILER_CLASSNAME_KEY = Metadata.Key.of("classname", Metadata.ASCII_STRING_MARSHALLER)
    }
}

/**
 * Returns whether the Envelope associated some P8eError's is retryable. IE can
 * <contract manager>.newContract(contractClazz: Class<T>, envelope: Envelope) be called.
 */
fun P8eError.isEnvelopeSpecRetryable(): Boolean = when (this) {
    is P8eError.AffiliateConnection -> false
    is P8eError.Authentication -> false
    is P8eError.ContractDefinition -> false
    is P8eError.ContractValidation -> false
    is P8eError.ProtoParse -> false
    is P8eError.NotFound -> false
    is P8eError.ExecutionError -> false
    is P8eError.PreExecutionError -> false
    is P8eError.Unknown -> false
}

/**
 * Returns whether the Contract associated some P8eError's is retryable. IE can <contract>.execute() be called again.
 */
fun P8eError.isContractRetryable(): Boolean = when (this) {
    is P8eError.AffiliateConnection -> true
    is P8eError.Authentication -> true
    is P8eError.ContractDefinition -> false
    is P8eError.ContractValidation -> false
    is P8eError.ProtoParse -> false
    is P8eError.NotFound -> false
    is P8eError.ExecutionError -> false
    is P8eError.PreExecutionError -> false
    is P8eError.Unknown -> false
}

/**
 * Returns a description of this error condition.
 */
fun P8eError.message(): String = when (this) {
    is P8eError.AffiliateConnection -> this.message
    is P8eError.Authentication -> this.message
    is P8eError.ContractDefinition -> this.message
    is P8eError.ContractValidation -> this.message
    is P8eError.ProtoParse -> this.message
    is P8eError.NotFound -> this.message
    is P8eError.ExecutionError -> this.envelopeError.message
    is P8eError.PreExecutionError -> this.t.cause?.stackTraceToString() ?: this.t.stackTraceToString()
    is P8eError.Unknown -> this.t.cause?.stackTraceToString() ?: this.t.stackTraceToString()
}

fun ContractScope.EnvelopeError.p8eError(): P8eError = P8eError.ExecutionError(this)

fun Throwable.p8eError(): P8eError = when (this) {
    is StatusException -> trailers?.get(P8eError.TRAILER_CLASSNAME_KEY)
    is StatusRuntimeException -> trailers?.get(P8eError.TRAILER_CLASSNAME_KEY)
    else -> null
}.let { classname ->
    when (classname) {
        AffiliateConnectionException::class.java.name -> P8eError.AffiliateConnection(message ?: "default affiliate connection")
        AuthenticationException::class.java.name -> P8eError.Authentication(message ?: "default authentication")
        ContractDefinitionException::class.java.name -> P8eError.ContractDefinition(message ?: "default contract definition")
        ContractValidationException::class.java.name -> P8eError.ContractValidation(message ?: "default contract validation")
        ProtoParseException::class.java.name -> P8eError.ProtoParse(message ?: "default proto parse")
        NotFoundException::class.java.name -> P8eError.NotFound(message ?: "default not found")
        else -> P8eError.Unknown(this)
    }
}
