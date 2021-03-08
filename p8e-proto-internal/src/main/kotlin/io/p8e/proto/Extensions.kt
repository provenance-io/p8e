package io.p8e.proto

import com.google.protobuf.DescriptorProtos
import com.google.protobuf.GeneratedMessage
import com.google.protobuf.ProtocolMessageEnum
import io.p8e.proto.ContractScope.Envelope.Status

/**
 *  Set of party types that are implicitly included on all contracts and can also be optionally satisfied or not.
 */
val implicitPartyTypes = setOf(ContractSpecs.PartyType.MARKER)

/**
 *  Set of party types that are address based as opposed to signer/key based.
 */
val addressPartyTypes = setOf(ContractSpecs.PartyType.MARKER)

fun Iterable<ContractSpecs.PartyType>.addImplicitParties() = this.plus(implicitPartyTypes).distinct()

fun Iterable<ContractSpecs.PartyType>.removeImplicitParties() = this.filterNot { implicitPartyTypes.contains(it) }

/**
 * Get enum description for a status value.
 */
fun Status.getDescription(): String {
    return getExtension(ContractScope.description)
}

/**
 * com.google.protobuf.ProtocolMessageEnum -> String Description
 */
fun <T> ProtocolMessageEnum.getExtension(extension: GeneratedMessage.GeneratedExtension<DescriptorProtos.EnumValueOptions, T>): T {
    try {
        return valueDescriptor.options.getExtension(extension)
    } catch (e: ArrayIndexOutOfBoundsException) {
        throw IllegalArgumentException("${javaClass.name}.$this missing extension [(${extension.descriptor.name}) = ???]. Try filtering it out")
    }
}
