package io.provenance.engine.index

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Descriptors.*
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType.*
import com.google.protobuf.Message
import io.p8e.classloader.ClassLoaderCache
import io.p8e.classloader.MemoryClassLoader
import io.p8e.crypto.SignerImpl
import io.p8e.definition.DefinitionService
import io.p8e.proto.ContractScope.Record
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.util.toHex
import io.provenance.os.client.OsClient
import io.p8e.proto.IndexProto.Index
import io.p8e.proto.IndexProto.Index.Behavior
import io.p8e.proto.IndexProto.Index.Behavior.*
import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.service.AffiliateService
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.security.KeyPair

@Component
class ProtoIndexer(
    private val objectMapper: ObjectMapper,
    private val osClient: OsClient
) {
    private val indexDescriptor = Index.getDefaultInstance().descriptorForType.file.findExtensionByName("index")
    private val messageIndexDescriptor = Index.getDefaultInstance().descriptorForType.file.findExtensionByName("message_index")
    private val _definitionService = DefinitionService(osClient)

    fun indexFields(scope: Scope, keyPairs: Map<String, KeyPair>, affiliateService: AffiliateService): Map<String, Any> =
        scope.recordGroupList
            // find all record groups where there's at least one party member that's an affiliate on this p8e instance
            .filter { group -> group.partiesList.any { keyPairs.containsKey(it.signer.encryptionPublicKey.toHex()) } }
            .flatMap { group ->
                group.recordsList
                    .map { fact ->
                        val keyPair: KeyPair = group.partiesList
                            .map { it.signer }
                            .first { keyPairs.containsKey(it.encryptionPublicKey.toHex()) }
                            .let { keyPairs.getValue(it.encryptionPublicKey.toHex()) }

                        // Need a reference of the signer that is used to verify signatures.
                        val signer = transaction { affiliateService.getSigner(keyPair.public) }

                        // Try to re-use MemoryClassLoader if possible for caching reasons
                        val spec = _definitionService.loadProto(keyPair, group.specification, ContractSpec::class.java.name, signer) as ContractSpec

                        val classLoaderKey = "${spec.definition.resourceLocation.ref.hash}-${spec.considerationSpecsList.first().outputSpec.spec.resourceLocation.ref.hash}"
                        val memoryClassLoader = ClassLoaderCache.classLoaderCache.computeIfAbsent(classLoaderKey) {
                            MemoryClassLoader("", ByteArrayInputStream(ByteArray(0)))
                        }

                        val definitionService = DefinitionService(osClient, memoryClassLoader)
                        loadAllJars(keyPair, definitionService, spec, signer)

                        fact.resultName to indexFields(
                            definitionService,
                            keyPair,
                            fact,
                            signer
                        )
                    }
            }.filter { it.second != null }
            .map { it.first to it.second!! }
            .toMap()

    @Suppress("UNCHECKED_CAST")
    fun <T: Message> indexFields(
        definitionService: DefinitionService,
        keyPair: KeyPair,
        t: T,
        signer: SignerImpl,
        indexParent: Boolean? = null
    ): Map<String, Any>? {
        val message = when(t) {
            is Record ->
                if (t.resultHash.isEmpty()) {
                    return mapOf()
                } else {
                    definitionService.forThread {
                        definitionService.loadProto(
                            keyPair,
                            t.resultHash,
                            t.classname,
                            signer
                        )
                    }
                }
            else -> t
        }

        val messageBehavior = message.descriptorForType.getIndex(messageIndexDescriptor)
        return message.descriptorForType
            .fields
            .map { fieldDescriptor ->
                val doIndex = indexField(
                    indexParent,
                    fieldDescriptor.getIndex(indexDescriptor)?.index,
                    messageBehavior?.index
                )

                when {
                    fieldDescriptor.isRepeated -> {
                        val list = (message.getField(fieldDescriptor) as List<Any>)
                        val resultList = mutableListOf<Any>()
                        for (i in 0 until list.size) {
                                getValue(
                                    definitionService,
                                    keyPair,
                                    fieldDescriptor,
                                    doIndex,
                                    list[i],
                                    signer
                                )?.let(resultList::add)
                        }
                        fieldDescriptor.jsonName to resultList.takeIf { it.isNotEmpty() }
                    }
                    fieldDescriptor.isMapField -> // Protobuf only allows Strings in the key field
                        fieldDescriptor.jsonName to (message.getField(fieldDescriptor) as Map<String, *>).mapValues { value ->
                            getValue(
                                definitionService,
                                keyPair,
                                fieldDescriptor,
                                doIndex,
                                message.getField(fieldDescriptor),
                                signer
                            )
                        }.takeIf { it.entries.any { it.value != null } }
                    else -> fieldDescriptor.jsonName to getValue(
                        definitionService,
                        keyPair,
                        fieldDescriptor,
                        doIndex,
                        message.getField(fieldDescriptor),
                        signer
                    )
                }
            }.filter { it.second != null }
            .takeIf { it.any { it.second != null }}
            ?.map { it.first to it.second!! }
            ?.toMap()
    }

    private fun getValue(
        definitionService: DefinitionService,
        keyPair: KeyPair,
        fieldDescriptor: FieldDescriptor,
        doIndex: Boolean,
        value: Any,
        signer: SignerImpl
    ): Any? {
        // Get value for primitive types, on MESSAGE recurse, else empty list
        return when (fieldDescriptor.javaType) {
            INT,
            LONG,
            FLOAT,
            DOUBLE,
            BOOLEAN,
            STRING,
            BYTE_STRING -> if (doIndex) { value } else { null }
            ENUM -> if (doIndex) { (value as EnumValueDescriptor).name } else { null }
            MESSAGE -> {
                indexFields(
                    definitionService,
                    keyPair,
                    value as Message,
                    signer,
                    doIndex
                )
            }
            else -> throw IllegalStateException("Unknown protobuf type ${fieldDescriptor.javaType}")
        }
    }

    private fun indexField(
        indexParent: Boolean?,
        fieldBehavior: Behavior?,
        messageBehavior: Behavior?
    ): Boolean {
        return when (fieldBehavior ?: messageBehavior) {
            ALWAYS -> true

            NEVER -> false

            INDEX_DEFER_PARENT -> {
                // Index if parent is true or unset (null)
                when (indexParent) {
                    null,
                    true -> true
                    false -> false
                }
            }

            UNRECOGNIZED,
            null,
            NOT_SET,
            NO_INDEX_DEFER_PARENT -> {
                // Don't index if parent is false or unset (null)
                when (indexParent) {
                    true -> true

                    null,
                    false -> false
                }
            }
        }
    }

    private fun loadAllJars(
        keyPair: KeyPair,
        definitionService: DefinitionService,
        spec: ContractSpec,
        signer: SignerImpl
    ) {
        mutableListOf(spec.definition)
            .apply {
                addAll(spec.inputSpecsList)
                addAll(
                    spec.conditionSpecsList
                        .flatMap { listOf(it.outputSpec.spec) }
                )
                addAll(
                    spec.considerationSpecsList
                        .flatMap { listOf(it.outputSpec.spec) }
                )
            }.forEach {
                definitionService.addJar(
                    keyPair,
                    it,
                    signer
                )
            }
    }
}

fun FieldDescriptor.getIndex(
    extensionDescriptor: FieldDescriptor
): Index? {
    // return options.getField(extensionDescriptor) as? Index
    // Pierce Trey - 03/10/2021 replaced the above line with the below to handle old versions of p8e-contract
    // that use the io.provenance.util namespaced Index class (while we only have access to the new io.p8e.util namespace)
    // which caused all fields to get skipped for indexing.
    // todo: change back to the old line once p8e-contract is converted to use the new index extension and
    // the old Index message/extension have been removed from provenance-corelib
    return options.getField(extensionDescriptor)?.let {
        try {
            Index.parseFrom((it as Message).toByteArray())
        } catch (t: Throwable) {
            logger().error("FieldDescriptor.getIndex failed to parse index extension with error", t)
            null
        }
    }
}

fun Descriptor.getIndex(
    extensionDescriptor: FieldDescriptor
): Index? {
    // return options.getField(extensionDescriptor) as? Index
    // Pierce Trey - 03/10/2021 replaced the above line with the below to handle old versions of p8e-contract
    // that use the io.provenance.util namespaced Index class (while we only have access to the new io.p8e.util namespace)
    // which caused all fields to get skipped for indexing.
    // todo: change back to the old line once p8e-contract is converted to use the new index extension and
    // the old Index message/extension have been removed from provenance-corelib
    return options.getField(extensionDescriptor)?.let {
        try {
            Index.parseFrom((it as Message).toByteArray())
        } catch (t: Throwable) {
            logger().error("Descriptor.getIndex failed to parse index extension with error", t)
            null
        }
    }
}
