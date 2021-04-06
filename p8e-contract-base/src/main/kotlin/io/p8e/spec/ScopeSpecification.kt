package io.p8e.spec

import io.p8e.proto.ContractSpecs.PartyType

/**
 * Provides a means to specify a Provenance scope specification.
 */
abstract class P8eScopeSpecification {
    abstract fun name(): String
    abstract fun description(): String
    abstract fun websiteUrl(): String
    abstract fun iconUrl(): String

    abstract fun partiesInvolved(): Array<PartyType>
}
