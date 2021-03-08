package io.provenance.pbc.p8e.ext

import io.p8e.proto.ContractSpecs.ContractSpec as P8EContractSpec
import io.provenance.pbc.proto.spec.ContractSpecProtos.ContractSpec as PBCContractSpec

fun P8EContractSpec.toPbc(): PBCContractSpec = PBCContractSpec.parseFrom(toByteArray())
