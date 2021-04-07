package io.p8e.spec

import com.google.protobuf.Message
import io.p8e.annotations.Prerequisite
import io.p8e.annotations.Function
import io.p8e.annotations.Fact
import io.p8e.annotations.Input
import io.p8e.annotations.Participants
import io.p8e.annotations.ScopeSpecification
import io.p8e.proto.Common.DefinitionSpec
import io.p8e.proto.Common.DefinitionSpec.Type.FACT
import io.p8e.proto.Common.DefinitionSpec.Type.FACT_LIST
import io.p8e.proto.Common.DefinitionSpec.Type.PROPOSED
import io.p8e.proto.Common.Location
import io.p8e.proto.Common.ProvenanceReference
import io.p8e.proto.ContractSpecs.ConditionSpec
import io.p8e.proto.ContractSpecs.ConsiderationSpec
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.ContractSpecs.PartyType
import io.p8e.proto.Contracts
import io.p8e.proto.Contracts.ConditionProto
import io.p8e.proto.Contracts.ConsiderationProto
import io.p8e.proto.Contracts.Contract
import io.p8e.proto.Contracts.Fact as FactProto
import io.p8e.proto.ProtoUtil
import io.p8e.util.*
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmName

object ContractSpecMapper {

    fun ContractSpec.newContract(): Contract.Builder =
        Contract.newBuilder()
            .setSpec(
                FactProto.newBuilder()
                    .setName(this.definition.name)
                    .setDataLocation(
                        Location.newBuilder()
                            .setClassname(ContractSpec::class.java.name)
                            .setRef(
                                ProvenanceReference.newBuilder()
                                    .setHash(this.toByteArray().base64Sha512())
                            )
                    )
            )
            .addAllInputs(this.inputSpecsList.map { it.newFact().build() })
            .addAllConditions(this.conditionSpecsList.map { it.newConditionProto().build() })
            .addAllConsiderations(this.considerationSpecsList.map { it.newConsiderationProto().build() })
            .addAllRecitals(this.partiesInvolvedList.map { it.newRecital().build() })

    fun ConditionSpec.newConditionProto() =
        ConditionProto.newBuilder()
            .setConditionName(this.funcName)

    fun ConsiderationSpec.newConsiderationProto() =
        ConsiderationProto.newBuilder()
            .setConsiderationName(this.funcName)

    fun DefinitionSpec.newFact() =
        FactProto.newBuilder()
            .setName(this.name)

    fun PartyType.newRecital() =
        Contracts.Recital.newBuilder()
            .setSignerRole(this)

    fun findRecital(clazz: KClass<out P8eContract>) = clazz.findAnnotation<Participants>()

    fun dehydrateSpec(
        clazz: KClass<out P8eContract>,
        contractRef: ProvenanceReference,
        protoRef: ProvenanceReference
    ): ContractSpec {

        // Verify that the contract is valid by checking for the appropriate java interface.
        clazz.isSubclassOf(P8eContract::class)
            .orThrowContractDefinition("Contract class ${clazz::class.java.name} is not a subclass of P8eContract")

        val scopeSpecifications = clazz.annotations
            .filter { it is ScopeSpecification }
            .map { it as ScopeSpecification }
            .flatMap { it.names.toList() }
            .takeUnless { it.isEmpty() }
            .orThrowContractDefinition("Class requires a ScopeSpecification annotation")

        val spec = ContractSpec.newBuilder()

        with(ProtoUtil) {
            spec.definition = defSpecBuilderOf(
                clazz.simpleName!!,
                locationBuilderOf(
                    clazz.jvmName,
                    contractRef
                ),
                FACT
            )
                .build()
        }

        clazz.constructors
            .takeIf { it.size == 1 }
            .orThrowContractDefinition("No constructor found, or more than one constructor identified")
            .first()
            .valueParameters
            .forEach { param ->
                val factAnnotation = param.findAnnotation<Fact>()
                    .orThrowContractDefinition("Constructor param(${param.name}) is missing @Fact annotation")

                with(ProtoUtil) {
                    if (List::class == param.type.classifier) {
                        val erasedType = (param.type.javaType as ParameterizedType)
                            .actualTypeArguments[0]
                            .let { it as Class<*> }
                            .takeIf {
                                Message::class.java.isAssignableFrom(it)
                            }.orThrowContractDefinition("Constructor parameter of type List<T> must have a type T that implements ${Message::class.java.name}")

                        spec.addInputSpecs(
                            defSpecBuilderOf(
                                factAnnotation.name,
                                locationBuilderOf(
                                    erasedType.name,
                                    protoRef
                                ),
                                FACT_LIST
                            )
                        )
                    } else {
                        spec.addInputSpecs(
                            defSpecBuilderOf(
                                factAnnotation.name,
                                locationBuilderOf(
                                    param.type.javaType.typeName,
                                    protoRef
                                ),
                                FACT
                            )
                        )
                    }
                }
            }

        // Add the recital to the contract spec.
        clazz.annotations
            .filter { it is Participants }
            .map { it as Participants }
            .flatMap { it.roles.toList() }
            .let(spec::addAllPartiesInvolved)

        clazz.functions
            .filter { it.findAnnotation<Prerequisite>() != null }
            .map { func ->
                buildConditionSpec(protoRef, func)
            }.let(spec::addAllConditionSpecs)

        clazz.functions
            .filter { it.findAnnotation<Function>() != null }
            .map { func ->
                buildConsiderationSpec(protoRef, func)
            }.let {
                spec.addAllConsiderationSpecs(it)
            }

        return spec.build()
    }

    private fun buildConditionSpec(
        protoRef: ProvenanceReference,
        func: KFunction<*>
    ): ConditionSpec {
        val builder = ConditionSpec.newBuilder()

        builder.funcName = func.name

        func.valueParameters
            .forEach { param ->
                param.findAnnotation<Fact>()
                    ?.also {
                        with(ProtoUtil) {
                            builder.addInputSpecs(
                                defSpecBuilderOf(
                                    it.name,
                                    locationBuilderOf(
                                        param.type.javaType.typeName,
                                        protoRef
                                    ),
                                    FACT
                                )
                            )
                        }
                    } ?: param.findAnnotation<Input>()
                    .orThrowNotFound("No @Input or @Fact Found for Param ${param}")
                    .also {
                        with(ProtoUtil) {
                            builder.addInputSpecs(
                                defSpecBuilderOf(
                                    it.name,
                                    locationBuilderOf(
                                        param.type.javaType.typeName,
                                        protoRef
                                    ),
                                    PROPOSED
                                )
                            )
                        }
                    }
            }

        func.findAnnotation<Fact>()
            .orThrowNotFound("No @Fact Found for Function ${func}")
            .also { fact ->
                with(ProtoUtil) {
                    builder.setOutputSpec(
                        outputSpecBuilderOf(
                            defSpecBuilderOf(
                                fact.name,
                                locationBuilderOf(
                                    func.returnType.javaType.typeName,
                                    protoRef
                                ),
                                PROPOSED
                            )
                        )
                    )
                }
            }

        return builder.build()
    }

    private fun buildConsiderationSpec(
        protoRef: ProvenanceReference,
        func: KFunction<*>
    ): ConsiderationSpec {
        val function = ConsiderationSpec.newBuilder()
        function.funcName = func.name

        val considerationDecorator = func.findAnnotation<Function>()
            .orThrowNotFound("Function Annotation not found on ${func}")
        function.responsibleParty = considerationDecorator.invokedBy

        func.valueParameters
            .forEach { param ->
                param.findAnnotation<Fact>()
                    ?.also {
                        with(ProtoUtil) {
                            function.addInputSpecs(
                                defSpecBuilderOf(
                                    it.name,
                                    locationBuilderOf(
                                        param.type.javaType.typeName,
                                        protoRef
                                    ),
                                    FACT
                                )
                            )
                        }
                    } ?: param.findAnnotation<Input>()
                    .orThrowNotFound("No @Input or @Fact Found for Param ${param}")
                    .also {
                        with(ProtoUtil) {
                            function.addInputSpecs(
                                defSpecBuilderOf(
                                    it.name,
                                    locationBuilderOf(
                                        param.type.javaType.typeName,
                                        protoRef
                                    ),
                                    PROPOSED
                                )
                            )
                        }
                    }
            }

        func.findAnnotation<Fact>()
            .orThrowNotFound("No @Fact Found for Function ${func}")
            .also { fact ->
                with(ProtoUtil) {
                    function.setOutputSpec(
                        outputSpecBuilderOf(
                            defSpecBuilderOf(
                                fact.name,
                                locationBuilderOf(
                                    func.returnType.javaType.typeName,
                                    protoRef
                                ),
                                PROPOSED
                            )
                        )
                    )
                }
            }

        return function.build()
    }
}
