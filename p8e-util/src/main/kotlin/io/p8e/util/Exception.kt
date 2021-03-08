package io.p8e.util

fun <T : Any> T?.orThrowNotFound(message: String) = this ?: throw NotFoundException(message)
fun <T : Any> T?.orThrowContractDefinition(message: String) = this ?: throw ContractDefinitionException(message)

class AffiliateConnectionException(message: String) : RuntimeException(message)
class AuthenticationException(message: String): RuntimeException(message)
class ContractDefinitionException(message: String): Exception(message)
class ContractValidationException(message: String) : RuntimeException(message)
class ProtoParseException(message: String): RuntimeException(message)
class NotFoundException(message: String) : RuntimeException(message)
