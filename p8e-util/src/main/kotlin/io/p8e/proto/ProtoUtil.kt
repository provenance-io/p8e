package io.p8e.proto

import com.google.protobuf.Message
import com.google.protobuf.Message.Builder
import com.google.protobuf.util.JsonFormat
import io.p8e.crypto.Pen
import io.p8e.crypto.SignerImpl.Companion.PROVIDER
import io.p8e.proto.Common.DefinitionSpec
import io.p8e.proto.Common.DefinitionSpec.Type
import io.p8e.proto.Common.Location
import io.p8e.proto.Common.OutputSpec
import io.p8e.proto.Common.ProvenanceReference
import io.p8e.proto.Common.Signature
import io.p8e.proto.Contracts.ProposedFact
import io.p8e.util.toProtoUuidProv
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.staticFunctions

object ProtoJsonUtil {
    val messageCache = mutableMapOf<KClass<out Message>, KFunction<*>>()
    inline fun <reified T: Message> String.toMessage() =
        (messageCache.computeIfAbsent(T::class) {
            T::class.staticFunctions.find { it.name == "newBuilder" && it.parameters.size == 0 }
                ?: throw IllegalStateException("Unable to find newBuilder function on ${T::class.java.name}")
        }.call() as Builder)
            .let {
                JsonFormat.parser().merge(this, it)
                it.build() as T
            }

    fun Message.toJson() = JsonFormat.printer().print(this)
}

object ProtoUtil {
    fun defSpecBuilderOf(name: String, location: Location.Builder, type: Type): DefinitionSpec.Builder =
        DefinitionSpec.newBuilder()
            .setName(name)
            .setType(type)
            .setResourceLocation(
                location
            )

    fun outputSpecBuilderOf(
        defSpec: DefinitionSpec.Builder
    ): OutputSpec.Builder {
        return OutputSpec.newBuilder()
            .setSpec(defSpec)
    }

    fun provenanceReferenceOf(scopeUuid: UUID, contractUuid: UUID, hash: String) =
        ProvenanceReference.newBuilder()
            .setScopeUuid(scopeUuid.toProtoUuidProv())
            .setGroupUuid(contractUuid.toProtoUuidProv())
            .setHash(hash)


    fun locationBuilderOf(classname: String, ref: ProvenanceReference) =
        Location.newBuilder()
            .setRef(ref)
            .setClassname(classname)

    fun signatureBuilderOf(signature: String, algorithm: String): Signature.Builder =
        Signature.newBuilder()
            .setAlgo(algorithm)
            .setProvider(PROVIDER)
            .setSignature(signature)


//    outputDef.name,
//    String(objectWithItem.obj.unencryptedSha512.base64Encode()),
//    message.javaClass.name,
//    scope?.uuid?.toUuidProv(),
//    ancestorHash
    fun proposedFactOf(name: String, hash: String, classname: String, scopeUuid: UUID? = null, ancestorHash: String? = null) =
        ProposedFact.newBuilder()
            .setClassname(classname)
            .setName(name)
            .setHash(hash)
            .apply {
                if (ancestorHash != null && scopeUuid != null) {
                    setAncestor(
                        ProvenanceReference.newBuilder()
                            .setScopeUuid(scopeUuid.toProtoUuidProv())
                            .setHash(ancestorHash)
                            .setName(name)
                    )
                }
            }
}
