package io.p8e.proto

import com.google.protobuf.Message
import io.p8e.annotations.Fact
import io.p8e.spec.ContractSpecMapper
import io.p8e.spec.P8eContract
import io.p8e.util.base64Sha512
import io.p8e.util.orThrowNotFound
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

object MessageMapper {
    fun Message.toUri() = this.toByteArray().base64Sha512().let { "object://local.provenance.io/${it}"}
    fun Message.toHash() = this.toByteArray().base64Sha512()
}

object P8eContractMapper {
    fun P8eContract.toContractSpec() =
        this::class.toContractSpec()

    fun KClass<out P8eContract>.toContractSpec() =
        ContractSpecMapper.dehydrateSpec(this, Common.ProvenanceReference.getDefaultInstance(), Common.ProvenanceReference.getDefaultInstance())

//    fun P8eContract.toContract(): Contract {
//        val clazz = this.getClazz()
//        val spec = ContractSpecMapper.dehydrateSpec(clazz)
//
//        val contract = spec.newContract()
//
//        with(ProtoUtil) {
//            contract.definition = defSpecBuilderOf(
//                clazz.simpleName!!,
//                locationBuilderOf(
//                    clazz.jvmName,
//                    ProvenanceReference.newBuilder().setHash(DefLocation.EXEC_HASH).build()
//                ),
//                Type.FACT
//            ).build()
//        }
//
//        // Map the constructor input values to the input list of the contract.
//        val inputs = contract.inputsList.map { it.name to it.toBuilder()}.toMap()
//        clazz.findConstructor().let { constructor ->
//            constructor.parameters.map { p ->
//                val factDef = p.getAnnotation(FactDef::class.java)
//                clazz.memberProperties.filter { it.name == factDef.name }.firstOrNull()?.call(this).also {
//                    val msg = Message::class.java.cast(it)
//                    inputs.get(factDef.name)!!.setDataLocation(
//                        Location.newBuilder()
//                            .setClassname(p.type.name)
//                            .setRef(ProvenanceReference.newBuilder().setHash(msg.toUri()))
//                    )
//                }
//            }
//        }
//
//        // update the inputs
//        contract.clearInputs().apply {
//            addAllInputs(inputs.map { it.value.build() }.toList())
//        }
//
//        // Satisfy each of the recitals with the recitals of the object.
//        contract.clearRecitals()
//        contract.addAllRecitals(this.recitals().map { it.build() })
//
//        return contract.build()
//    }

//    fun P8eContract.getConstructorFactValues(): List<Message> {
//        val clazz = this.getClazz()
//        val values = mutableListOf<Message>()
//        clazz.findConstructor().let { constructor ->
//            constructor.parameters.map { p ->
//                val factDef = p.getAnnotation(FactDef::class.java)
//                clazz.memberProperties.filter { it.name == factDef.name }.firstOrNull()?.call(this).also {
//                    Message::class.java.cast(it).also { values.add(it) }
//                }
//            }
//        }
//        return values
//    }

    // TODO - cache the reflection
//    fun Class<out P8eContract>.findConstructor() = this.kotlin.findConstructor()
    fun KClass<out P8eContract>.findConstructor() =
        this.java.declaredConstructors
            .filter {
                // Verify that all params to the constructor are FactDefs
                it.parameters.filter { param -> param.isAnnotationPresent(Fact::class.java) }.size == it.parameterCount
            }
            .first()
            .orThrowNotFound("Constructor not found for ${this::class.jvmName}")
}
