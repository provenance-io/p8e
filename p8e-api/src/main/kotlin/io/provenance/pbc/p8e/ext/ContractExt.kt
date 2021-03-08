package io.provenance.pbc.p8e.ext

import io.p8e.proto.Contracts.Contract as P8EContract
import io.provenance.pbc.proto.contract.ContractProtos.Contract as PbcContract

fun P8EContract.toPbc(): PbcContract = PbcContract.parseFrom(toByteArray())
