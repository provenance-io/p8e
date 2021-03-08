package io.provenance.pbc.clients.jackson

import com.fasterxml.jackson.databind.module.SimpleModule
import com.google.protobuf.Message
import io.provenance.pbc.proto.contract.ContractProtos.Contract
import io.provenance.pbc.proto.contract.ScopeProtos.Scope
import io.provenance.pbc.proto.spec.ContractSpecProtos.ContractSpec
import io.provenance.pbc.proto.types.TypesProtos.SignatureSet

class SimpleClientProtoModule : SimpleModule("protobuf-binary") {
    override fun setupModule(context: SetupContext) {
        addSerializer(Message::class.java, ProtoSerializer.INSTANCE)

        addDeserializer(ContractSpec::class.java, ProtoDeserializer(ContractSpec.getDefaultInstance()))
        addDeserializer(Contract::class.java, ProtoDeserializer(Contract.getDefaultInstance()))
        addDeserializer(Scope::class.java, ProtoDeserializer(Scope.getDefaultInstance()))
        addDeserializer(SignatureSet::class.java, ProtoDeserializer(SignatureSet.getDefaultInstance()))

        super.setupModule(context)
    }
}
