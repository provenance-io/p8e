package io.provenance.engine.grpc.v1

import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import io.p8e.grpc.complete
import io.p8e.proto.ChaincodeGrpc.ChaincodeImplBase
import io.p8e.proto.Domain.SpecRequest
import io.p8e.util.base64Decode
import io.p8e.util.toByteString
import io.p8e.util.toMessageWithStackTrace
import io.p8e.util.toUuidProv
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.engine.service.ChaincodeInvokeService
import io.provenance.engine.service.ProvenanceGrpcService
import io.provenance.engine.util.toProvHash
import io.provenance.p8e.shared.domain.ScopeSpecificationRecord
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.sql.batchInsertOnConflictIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.lognet.springboot.grpc.GRpcService
import java.time.OffsetDateTime
import java.util.UUID

@GRpcService(interceptors = [JwtServerInterceptor::class, UnhandledExceptionInterceptor::class])
class ChaincodeGrpc(
    private val chaincodeInvokeService: ChaincodeInvokeService,
    private val provenanceGrpcService: ProvenanceGrpcService,
): ChaincodeImplBase() {
    val log = logger()

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
            .associateBy { it.name }

        val specPairs = request.contractSpecList.zip(request.specMappingList)

        val incomingScopeSpecIdToContractSpecHashes =
            specPairs.fold(mutableMapOf<UUID, MutableCollection<ByteString>>()) { acc, curr ->
                curr.second.scopeSpecificationsList.forEach {
                    val scopeSpecUuid = scopeSpecificationsByName.get(it)!!.id.value
                    acc.getOrPut(scopeSpecUuid) { mutableListOf() }
                        .add(curr.first.toProvHash().base64Decode().toByteString())
                }

                acc
            }

        val incomingAndHistoricalScopeSpecIdToContractSpecHashes =
            scopeSpecificationsByName.values.map { it.id.value }.associateWith {
                provenanceGrpcService.getScopeSpecification(it).contractSpecIdsList
                    .plus(incomingScopeSpecIdToContractSpecHashes.getOrDefault(it, mutableListOf()))
                    .distinct()
            }

        chaincodeInvokeService.addContractSpecs(
            scopeSpecificationsByName.values,
            incomingAndHistoricalScopeSpecIdToContractSpecHashes,
            request.contractSpecList
        )
        
        Empty.getDefaultInstance().complete(responseObserver)
    }
}
