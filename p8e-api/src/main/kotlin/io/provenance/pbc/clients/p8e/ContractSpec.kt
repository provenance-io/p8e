package io.provenance.pbc.clients.p8e

import io.provenance.pbc.clients.ContractSpecs
import io.provenance.pbc.clients.SubmitContractSpecRequest
import io.provenance.pbc.clients.tx.TxPreparer
import io.provenance.pbc.p8e.ext.toPbc
import org.slf4j.LoggerFactory

import io.p8e.proto.ContractSpecs.ContractSpec as P8EContractSpec

private val log = LoggerFactory.getLogger(ContractSpecs::class.java)

// TODO can this class be removed?
fun ContractSpecs.addP8EContractSpec(spec: P8EContractSpec): TxPreparer = { base ->
    log.trace("addContractSpec(name:${spec.definition.name})")
    prepareSubmitContractSpec(SubmitContractSpecRequest(base, spec.toPbc()))
}
