package io.provenance.engine.grpc.v1

import com.google.protobuf.Empty
import io.grpc.stub.StreamObserver
import io.p8e.grpc.complete
import io.p8e.proto.ChaincodeGrpc.ChaincodeImplBase
import io.p8e.proto.Domain.SpecRequest
import io.provenance.engine.grpc.interceptors.JwtServerInterceptor
import io.provenance.engine.grpc.interceptors.UnhandledExceptionInterceptor
import io.provenance.engine.service.ChaincodeInvokeService
import org.lognet.springboot.grpc.GRpcService

@GRpcService(interceptors = [JwtServerInterceptor::class, UnhandledExceptionInterceptor::class])
class ChaincodeGrpc(
    private val chaincodeInvokeService: ChaincodeInvokeService
): ChaincodeImplBase() {
    override fun addSpec(
        request: SpecRequest,
        responseObserver: StreamObserver<Empty>
    ) {
        chaincodeInvokeService.addContractSpecs(request.specList)
        Empty.getDefaultInstance().complete(responseObserver)
    }
}