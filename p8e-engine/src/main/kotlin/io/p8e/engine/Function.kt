package io.p8e.engine

import arrow.core.Either
import com.google.protobuf.Message
import io.p8e.annotations.Fact
import io.p8e.annotations.Input
import io.p8e.crypto.SignerImpl
import io.p8e.definition.DefinitionService
import io.p8e.proto.Common.DefinitionSpec.Type
import io.p8e.proto.Common.ProvenanceReference
import io.p8e.proto.Contracts.ConsiderationProto
import io.p8e.proto.Contracts.ProposedFact
import io.p8e.proto.ProtoUtil
import io.p8e.spec.P8eContract
import io.p8e.util.ContractDefinitionException
import io.provenance.p8e.encryption.model.KeyRef
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import kotlin.Function

class Function<T: P8eContract>(
    private val encryptionKeyRef: KeyRef,
    private val signer: SignerImpl,
    definitionService: DefinitionService,
    private val contract: T,
    val considerationBuilder: ConsiderationProto.Builder,
    val method: Method,
    facts: List<FactInstance>
): Function<Message> {

    val fact = method.getAnnotation(Fact::class.java)
        ?: throw ContractDefinitionException("${contract.javaClass.name}.${method.name} must have the ${Fact::class.java.name} annotation.")

    private val methodParameters = getFunctionParameters(
        encryptionKeyRef,
        considerationBuilder,
        method,
        facts,
        definitionService
    )

    fun canExecute(): Boolean {
        return methodParameters.size == method.parameters.size
    }

    operator fun invoke(): Pair<ConsiderationProto.Builder, Message?> {
        return considerationBuilder to method.invoke(contract, *methodParameters.toTypedArray()) as? Message
    }

    private fun getFunctionParameters(
        encryptionKeyRef: KeyRef,
        considerationProto: ConsiderationProto.Builder,
        method: Method,
        facts: List<FactInstance>,
        definitionService: DefinitionService
    ): List<Message> {
        val proposed = considerationProto.inputsList
            .map { proposedFact ->
                val message = definitionService.loadProto(
                    encryptionKeyRef,
                    proposedFact.let(::proposedFactToDef),
                    signer = signer,
                    signaturePublicKey = signer.getPublicKey()
                )
                FactInstance(
                    proposedFact.name,
                    message.javaClass,
                    Either.Left(message)
                )
            }
        return method.parameters.mapNotNull { parameter ->
            getFunctionParameterFact(
                parameter,
                facts,
                proposed
            )
        }.map { (it.messageOrCollection as Either.Left).value }
    }

    private fun getFunctionParameterFact(
        parameter: Parameter,
        facts: List<FactInstance>,
        proposed: List<FactInstance>
    ): FactInstance? {
        val fact = parameter.getAnnotation(Fact::class.java)
        val input = parameter.getAnnotation(Input::class.java)
        if (fact != null && input != null ||
            fact == null && input == null) {
            throw ContractDefinitionException("Method parameter ${parameter.name} of type ${parameter.type.name} must have only one of (${Fact::class.java.name}|${Input::class.java.name}) annotations")
        }
        return if (fact != null) {
            facts.find {
                it.name == fact.name
            }
        } else {
            proposed.find {
                it.name == input.name
            }
        }?.takeIf {
            parameter.type == it.clazz
        }
    }

    private fun proposedFactToDef(proposedFact: ProposedFact) =
        proposedFact.let {
            ProtoUtil.defSpecBuilderOf(
                it.name,
                ProtoUtil.locationBuilderOf(
                    proposedFact.classname,
                    ProvenanceReference.newBuilder().setHash(proposedFact.hash).build()
                ),
                Type.FACT).build()
        }
}
