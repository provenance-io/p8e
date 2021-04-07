package io.p8e.annotations

import io.p8e.proto.ContractSpecs.PartyType

/**
 * Prerequisites are always executed by all parties and the location provided via uri is an executable location.
 *
 * Prerequisites must be satisfied before any further steps are evaluated on a contract during recording.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Prerequisite

/**
 * Functions are executed by all parties if all provided Facts/Inputs are available.
 * The location provided via uri is an executable location.
 *
 * The step of a contract that parties must execute and agree on in order to have the fact recorded in the scope on chain.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Function(val invokedBy: PartyType)

/**
 * Injects a data element that can be validated against a current recorded contract on chain.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Fact(val name: String)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Input(val name: String)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Participants(val roles: Array<PartyType>)

/**
 * Denotes a String that is used to implement a logical grouping of P8eContracts that operate on the same scope.
 * Can be thought of as a whitelist that specifies which P8eContracts can be executed for a scope. Name elements
 * must be associated with declarations of ScopeSpecificationDefinition.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ScopeSpecification(val names: Array<String>)

/**
 * Declaritively specifies a scope specification definition. This must be associated with a subclass of
 * P8eScopeSpecification.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ScopeSpecificationDefinition(
    val name: String,
    val description: String,
    val partiesInvolved: Array<PartyType>,
    val websiteUrl: String = "",
    val iconUrl: String = "",
)
