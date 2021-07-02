package io.p8e.engine

import com.google.protobuf.Message
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.p8e.classloader.ClassLoaderCache
import io.p8e.classloader.MemoryClassLoader
import io.p8e.crypto.Pen
import io.p8e.definition.DefinitionService
import io.p8e.extension.withoutLogging
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.Contracts
import io.p8e.proto.Contracts.Contract
import io.p8e.proto.Contracts.ExecutionResult
import io.p8e.proto.Contracts.ExecutionResult.Result.FAIL
import io.p8e.proto.Contracts.ExecutionResult.Result.SKIP
import io.p8e.proto.ProtoUtil.proposedFactOf
import io.p8e.util.*
import io.provenance.p8e.shared.extension.logger
import io.p8e.util.toUuidProv
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.os.client.OsClient
import io.provenance.p8e.shared.domain.AffiliateSharePublicKeys
import io.provenance.p8e.shared.service.AffiliateService
import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.PublicKey
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlin.concurrent.thread

interface P8eContractEngine {
    fun handle(keyPair: KeyPair, signingKeyPair: KeyPair, envelope: Envelope, pen: Pen): Envelope
}

class ContractEngine(
    private val osClient: OsClient,
    private val affiliateService: AffiliateService
): P8eContractEngine {

    private val _definitionService = DefinitionService(osClient)

    private val log = logger()

    override fun handle(
        keyPair: KeyPair,
        signingKeyPair: KeyPair,
        envelope: Envelope,
        pen: Pen
    ): Envelope {
        log.info("Running contract engine")

        val contract = envelope.contract
        val scope = envelope.scope.takeIf { it != Scope.getDefaultInstance() }

        val spec = timed("ContractEngine_fetchSpec") {
            _definitionService.loadProto(keyPair, contract.spec.dataLocation) as? ContractSpec
                ?: throw ContractDefinitionException("Spec stored at contract.spec.dataLocation is not of type ${ContractSpec::class.java.name}")
        }

        val classLoaderKey = "${spec.definition.resourceLocation.ref.hash}-${contract.definition.resourceLocation.ref.hash}-${spec.considerationSpecsList.first().outputSpec.spec.resourceLocation.ref.hash}"
        val memoryClassLoader = ClassLoaderCache.classLoaderCache.computeIfAbsent(classLoaderKey) {
            MemoryClassLoader("", ByteArrayInputStream(ByteArray(0)))
        }

        return memoryClassLoader.forThread {
            internalRun(
                contract,
                envelope,
                keyPair,
                memoryClassLoader,
                pen,
                scope,
                signingKeyPair,
                spec
            )
        }
    }

    private fun internalRun(
        contract: Contract,
        envelope: Envelope,
        keyPair: KeyPair,
        memoryClassLoader: MemoryClassLoader,
        pen: Pen,
        scope: Scope?,
        signingKeyPair: KeyPair,
        spec: ContractSpec
    ): Envelope {
        val definitionService = DefinitionService(osClient, memoryClassLoader)

        // Load contract spec class
        val contractSpecClass = timed("ContractEngine_loadSpecClass") {
            try {
                definitionService.loadClass(keyPair, spec.definition)
            } catch (e: StatusRuntimeException) {
                if (e.status.code == Status.Code.NOT_FOUND) {
                    throw ContractDefinitionException(
                        """
                            Unable to load contract jar. Verify that you're using a jar that has been bootstrapped.
                            [classname: ${spec.definition.resourceLocation.classname}]
                            [public key: ${keyPair.public.toHex()}]
                            [hash: ${spec.definition.resourceLocation.ref.hash}]
                        """.trimIndent()
                    )
                }
                throw e
            }
        }

        // Ensure all the classes listed in the spec are loaded into the MemoryClassLoader
        timed("ContractEngine_loadAllClasses") {
            loadAllClasses(
                keyPair,
                definitionService,
                spec
            )
        }

        // validate contract
        contract.validateAgainst(spec)

        when (contract.type!!) {
            // Contracts.ContractType.CHANGE_SCOPE -> {
            //     if (scope != null &&
            //         envelope.status == Envelope.Status.CREATED &&
            //         contract.invoker.encryptionPublicKey == keyPair.public.toPublicKeyProto()
            //     ) {
            //         val audience = scope.partiesList.map { it.signer.signingPublicKey }
            //             .plus(contract.recitalsList.map { it.signer.signingPublicKey })
            //             .map { it.toPublicKey() }
            //             .let { it + affiliateService.getSharePublicKeys(it).value }
            //             .toSet()

            //         log.debug("Change scope ownership - adding ${audience.map { it.toHex() }} [scope: ${scope.uuid.value}] [executionUuid: ${envelope.executionUuid.value}]")

            //         this.getScopeData(keyPair, definitionService, scope)
            //             .threadedMap(executor) { definitionService.save(keyPair, it, signingKeyPair, audience) }
            //     } else { }
            // }
            Contracts.ContractType.CHANGE_SCOPE -> throw IllegalStateException("CHANGE_SCOPE contract type is not implemented")
            Contracts.ContractType.FACT_BASED, Contracts.ContractType.UNRECOGNIZED -> Unit
        } as Unit

        val contractBuilder = contract.toBuilder()
        val contractWrapper = ContractWrapper(
            executor,
            contractSpecClass,
            keyPair,
            definitionService,
            contractBuilder,
            signingKeyPair
        )

        val prerequisiteResults = contractWrapper.prerequisites.map { prerequisite ->
            val (conditionBuilder, result) = try {
                withoutLogging { prerequisite.invoke() }
            } catch (t: Throwable) {
                // Abort execution on a failed prerequisite
                log.error(
                    "Error executing prerequisite ${contractWrapper.contractClass}.${prerequisite.method.name} [Exception classname: ${t.javaClass.name}]"
                )
                prerequisite.conditionProto.result = failResult(t)

                val contractForSignature = contractBuilder.build()
                return envelope.toBuilder()
                    .setContract(contractForSignature)
                    .addSignatures(pen.sign(contractForSignature))
                    .build()
            }

            if (result == null) {
                throw ContractDefinitionException(
                    """
                        Invoked function returned null instead of type ${Message::class.java.name}
                        [class: ${contractWrapper.contractClass.name}]
                        [invoked function: ${prerequisite.method.name}]
                    """.trimIndent()
                )
            }

            ResultSetter {
                conditionBuilder.result = signAndStore(
                    definitionService,
                    prerequisite.fact.name,
                    result,
                    contract.toAudience(envelope, scope),
                    signingKeyPair,
                    keyPair,
                    scope
                )
            }
        }

        val (execList, skipList) = contractWrapper.functions.partition { it.canExecute() }
        val functionResults =
            execList
                .map { function ->
                    val (considerationBuilder, result) = try {
                        withoutLogging { function.invoke() }
                    } catch (t: Throwable) {
                        // Abort execution on a failed condition
                        log.error(
                            "Error executing condition ${contractWrapper.contractClass}.${function.method.name} [Exception classname: ${t.javaClass.name}]"
                        )
                        function.considerationBuilder.result = failResult(t)

                        val contractForSignature = contractBuilder.build()
                        return envelope.toBuilder()
                            .setContract(contractForSignature)
                            .addSignatures(pen.sign(contractForSignature))
                            .build()
                    }
                    if (result == null) {
                        throw ContractDefinitionException(
                            """
                                Invoked function returned null instead of type ${Message::class.java.name}
                                [class: ${contractWrapper.contractClass.name}]
                                [invoked function: ${function.method.name}]
                            """.trimIndent()
                        )
                    }
                    ResultSetter {
                        considerationBuilder.result = signAndStore(
                            definitionService,
                            function.fact.name,
                            result,
                            contract.toAudience(envelope, scope),
                            signingKeyPair,
                            keyPair,
                            scope
                        )
                    }
                } + skipList.map { function ->
                    ResultSetter {
                        function.considerationBuilder.result = ExecutionResult.newBuilder().setResult(SKIP).build()
                    }
                }


        timed("ContractEngine_saveResults") {
            prerequisiteResults.toMutableList()
                .apply { addAll(functionResults) }
                .also { logger().info("Saving ${it.size} results for ContractEngine.handle") }
                .threadedMap(executor) { resultSetter -> resultSetter.setter().run { null } }
        }

        val contractForSignature = contractBuilder.build()
        return envelope.toBuilder()
            .setContract(contractForSignature)
            .addSignatures(pen.sign(contractForSignature))
            .build()
    }

    private fun getScopeData(
        keyPair: KeyPair,
        definitionService: DefinitionService,
        scope: Scope
    ): List<ByteArray> =
        scope.recordGroupList.flatMap { it.recordsList }
            .map { record -> record.inputsList.map { input -> Pair(input.classname, input.hash) } + Pair("unset", record.hash) + Pair(record.classname, record.resultHash) }
            .flatten()
            .plus(scope.recordGroupList.map { Pair(it.classname, it.specification) })
            .toSet()
            .threadedMap(executor) { (classname, hash) -> definitionService.get(keyPair, hash = hash, classname = classname).readAllBytes() }
            .toList()

    private fun loadAllClasses(
        keyPair: KeyPair,
        definitionService: DefinitionService,
        spec: ContractSpec
    ) {
        mutableListOf(spec.definition)
            .apply {
                add(
                    spec.considerationSpecsList
                        .first()
                        .outputSpec
                        .spec
                )
            }.threadedMap(executor) { definition ->
                with (definition.resourceLocation) {
                    this to definitionService.get(keyPair, hash = this.ref.hash, classname = this.classname)
                }
            }.toList()
            .forEach { (location, inputStream) ->  definitionService.addJar(location.ref.hash, inputStream) }
    }

    private fun signAndStore(
        definitionService: DefinitionService,
        name: String,
        message: Message,
        audiences: Set<PublicKey>,
        signingKeyPair: KeyPair,
        keyPair: KeyPair,
        scope: Scope?
    ): ExecutionResult {
        val sha512 = definitionService.save(
            keyPair,
            message,
            signingKeyPair,
            audiences
        )

        val ancestorHash = scope?.recordGroupList
            ?.flatMap { it.recordsList }
            ?.find { it.resultName == name }
            ?.resultHash

        return ExecutionResult.newBuilder()
            .setResult(ExecutionResult.Result.PASS)
            .setOutput(proposedFactOf(
                name,
                String(sha512.base64Encode()),
                message.javaClass.name,
                scope?.uuid?.toUuidProv(),
                ancestorHash
            )
            ).build()
    }

    private fun failResult(t: Throwable): ExecutionResult {
        return ExecutionResult
            .newBuilder()
            .setResult(FAIL)
            .setErrorMessage(t.toMessageWithStackTrace())
            .build()
    }

    companion object {
        private val executor = ThreadPoolFactory.newFixedThreadPool(
            System.getenv("CONTRACT_ENGINE_THREAD_POOL_SIZE")
                ?.takeIf { it.isNotEmpty() }
                ?.toInt() ?: 32,
            "contract-engine-%d"
        )
    }
}

data class ResultSetter(val setter: () -> Unit)

fun Contract.toAudience(envelope: Envelope, scope: Scope?): Set<PublicKey> = recitalsList
    .filter { it.hasSigner() }
    .map { it.signer.encryptionPublicKey }
    .plus(
        scope?.partiesList
            ?.filter { it.hasSigner() }
            ?.map { it.signer.encryptionPublicKey }
            ?.filter { it.isInitialized }
            ?: listOf()
    )
    .map { ECUtils.convertBytesToPublicKey(it.publicKeyBytes.toByteArray()) }
    .plus(envelope.affiliateSharesList.map { it.toPublicKey() })
    .toSet()

fun<T, K> Collection<T>.threadedMap(executor: ExecutorService, fn: (T) -> K): Collection<K> =
    this.map { item ->
        CompletableFuture<K>().also { future ->
            thread(start = false) {
                try {
                    future.complete(fn(item))
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }.let(executor::submit)
        }
    }.map { it.get() }
