package io.provenance.engine.grpc.v1

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import io.p8e.grpc.complete
import io.p8e.proto.ChaincodeGrpc.ChaincodeImplBase
import io.p8e.proto.Domain.SpecRequest
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.engine.service.ChaincodeInvokeService
import io.provenance.engine.util.toProvHash
import io.provenance.p8e.shared.domain.CST
import io.provenance.p8e.shared.domain.ContractSpecificationRecord
import io.provenance.p8e.shared.domain.ContractSpecificationTable
import io.provenance.p8e.shared.domain.ScopeSpecificationRecord
import io.provenance.p8e.shared.sql.batchInsertOnConflictIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.lognet.springboot.grpc.GRpcService
import java.time.OffsetDateTime

@GRpcService(interceptors = [JwtServerInterceptor::class, UnhandledExceptionInterceptor::class])
class ChaincodeGrpc(
    private val chaincodeInvokeService: ChaincodeInvokeService
): ChaincodeImplBase() {
    override fun addSpec(
        request: SpecRequest,
        responseObserver: StreamObserver<Empty>
    ) {
        transaction {
            request.scopeSpecList.forEach { scopeSpec ->
                ScopeSpecificationRecord.insertOrUpdate(scopeSpec.name) {
                    it.description = scopeSpec.description
                    it.partiesInvolved = scopeSpec.partiesInvolvedList.map { p -> p.name }.toTypedArray()
                    it.websiteUrl = scopeSpec.websiteUrl
                    it.iconUrl = scopeSpec.iconUrl
                    it.updated = OffsetDateTime.now()
                }
            }
        }

        val scopeSpecificationsByName = request.specMappingList
            .flatMap { it.scopeSpecificationsList }
            .let { transaction { ScopeSpecificationRecord.findByNames(it).toList() } }
            .map { it.name to it }
            .toMap()

        val specPairs = request.contractSpecList.zip(request.specMappingList)

        transaction {
            ContractSpecificationTable.batchInsertOnConflictIgnore(specPairs) { batch, specPair ->
                val hash = specPair.first.definition.resourceLocation.ref.hash
                val provenanceHash = specPair.first.toProvHash()

                specPair.second.scopeSpecificationsList.forEach { scopeSpecificationName ->
                    batch[CST.hash] = hash
                    batch[CST.provenanceHash] = provenanceHash
                    batch[CST.scopeSpecificationUuid] = scopeSpecificationsByName[scopeSpecificationName]?.id?.value
                        ?: throw IllegalArgumentException("Contract specification contains a scope specification that does not exist.")
                }
            }
        }

        val historicalContractSpecs = transaction {
            ContractSpecificationRecord.findByScopeSpecifications(scopeSpecificationsByName.values.map { it.id.value })
                .toList()
        }

        chaincodeInvokeService.addContractSpecs(scopeSpecificationsByName.values, historicalContractSpecs, request.contractSpecList)

        Empty.getDefaultInstance().complete(responseObserver)
    }
}
