package io.p8e.proxy

import arrow.core.Either
import com.google.protobuf.Message
import io.p8e.ContractManager
import io.p8e.client.P8eClient
import io.p8e.crypto.proto.CryptoProtos.Address
import io.p8e.crypto.proto.CryptoProtos.AddressType
import io.p8e.exception.P8eError
import io.p8e.proto.*
import io.p8e.proto.Common.DefinitionSpec
import io.p8e.proto.Common.DefinitionSpec.Type.PROPOSED
import io.p8e.proto.Common.Location
import io.p8e.proto.Common.ProvenanceReference
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.Record
import io.p8e.proto.ContractScope.Scope
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.ContractSpecs.PartyType
import io.p8e.proto.Contracts
import io.p8e.proto.Contracts.ExecutionResult
import io.p8e.proto.Contracts.Fact
import io.p8e.proto.Contracts.FactOrBuilder
import io.p8e.proto.Contracts.ProposedFact
import io.p8e.proto.Contracts.Recital
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.proto.Envelope.EnvelopeEvent.Action
import io.p8e.spec.P8eContract
import io.p8e.util.*
import io.p8e.util.toByteString
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.engine.crypto.Bech32
import io.p8e.proto.Util
import java.security.PublicKey
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KFunction
import io.p8e.proto.Contracts.Contract as ProtoContract

sealed class RecitalData
class RecitalPublicKey(val key: PublicKey): RecitalData()
class RecitalAddress(val address: ByteArray): RecitalData()

data class ContractError<T : P8eContract>(
    val contractClazz: Class<T>,
    val event: EnvelopeEvent,
    val error: ContractScope.EnvelopeError,
)

class Contract<T: P8eContract>(
    private val contractManager: ContractManager,
    private val client: P8eClient,
    val spec: ContractSpec,
    val envelope: Envelope,
    val contractClazz: Class<T>,
    private val executor: (EnvelopeEvent) -> Either<P8eError, Contract<T>>,
    val isFragment: Boolean = false,
    val constructedFromEvent: EnvelopeEvent? = null
) {
    private var stagedPrevExecutionUuid: Util.UUID? = null
    private var stagedExecutionUuid = envelope.executionUuid
    private var stagedExpirationTime: OffsetDateTime? = null
    private val stagedFacts = mutableMapOf<String, Pair<DefinitionSpec, Pair<ProvenanceReference, Message>>>()
    private val stagedCrossScopeFacts = mutableMapOf<String, Pair<ProvenanceReference, Message>>()
    private val stagedCrossScopeCollectionFacts = mutableMapOf<String, List<Pair<ProvenanceReference, Message>>>()
    private val stagedProposedFacts = mutableMapOf<String, Pair<DefinitionSpec, Message>>()
    private val stagedRecital = mutableListOf<Pair<PartyType, RecitalData>>()
    private val stagedProposedProtos = mutableListOf<Message>()
    private var stagedContract = ProtoContract.getDefaultInstance()
    private var executionEnvelope: Envelope = Envelope.getDefaultInstance()

    // Avoids us double processing.
    private val executed = AtomicBoolean(false)

    init {
        // Copy the recital list forward if it was previously defined..
        // this is a bit confusing..it might be clearer if there's another check we can make to only do this
        // for the subset of contracts that depend on it (multi-step??). The confusion comes from the fact that
        // recitals with addresses instead of signers never have a signer, but this filter is depending on the fact
        // that only the signerRole will be specified in a lot of cases.
        envelope.contract.recitalsList
            .filter { it.hasSigner() }
            .forEach { satisfyParticipant(it.signerRole, it.signer.signingPublicKey.toPublicKey()) }
    }

    fun newExecution(uuid: UUID = UUID.randomUUID()) {
        stagedPrevExecutionUuid = envelope.executionUuid
        stagedExecutionUuid = uuid.toProtoUuidProv()
    }

    fun setExpiration(expirationTime: OffsetDateTime) {
        stagedExpirationTime = expirationTime
    }

    fun getScopeUuid(): UUID {
        return envelope.ref.scopeUuid.toUuidProv()
    }

    fun hydrate(): T {
        return ContractProxy.newProxy(
            contractManager,
            envelope.contract,
            contractClazz
        )
    }

    fun status(): Envelope.Status {
        return contractManager.status(this)
    }

    fun reject(message: String = "") {
        contractManager.reject(this, message)
    }

    // For Java usage
    fun reject() {
        reject("")
    }

    fun cancel(message: String = "") {
        contractManager.cancel(this, message)
    }

    // For Java usage
    fun cancel() {
        cancel("")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T: Message> getProposedFact(
        clazz: Class<T>,
        proposedDefName: String
    ): T? {
        return envelope.contract
            .considerationsList
            .flatMap { it.inputsList }
            .find { it.classname == clazz.name && it.name == proposedDefName }
            ?.let { proposedFact -> client.loadProto(proposedFact.hash, proposedFact.classname) as T }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message> getResult(method: KFunction<OUT>): OUT? {
        val factName = (method.annotations.find { it.annotationClass == io.p8e.annotations.Fact::class } as? io.p8e.annotations.Fact)
            .orThrowContractDefinition("Method ${method.name} is not annotated with a Fact")
            .name

        return envelope.contract
            .considerationsList
            .map { it.result }
            .find { it.output.name == factName }
            ?.let { result -> client.loadProto(result.output.hash, result.output.classname) as OUT }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message> getResult(method: Function<OUT, T>): OUT? {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .find { it.name == factName }
            ?.let { fact -> client.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname) as OUT }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message, ARG1> getResult(method: Function1<OUT, T, ARG1>): OUT? {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .find { it.name == factName }
            ?.let { fact -> client.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname) as OUT }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message, ARG1, ARG2> getResult(method: Function2<OUT, T, ARG1, ARG2>): OUT? {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .find { it.name == factName }
            ?.let { fact -> client.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname) as OUT }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message, ARG1, ARG2, ARG3> getResult(method: Function3<OUT, T, ARG1, ARG2, ARG3>): OUT? {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .find { it.name == factName }
            ?.let { fact -> client.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname) as OUT }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message, ARG1, ARG2, ARG3, ARG4> getResult(method: Function4<OUT, T, ARG1, ARG2, ARG3, ARG4>): OUT? {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .find { it.name == factName }
            ?.let { fact -> client.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname) as OUT }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message, ARG1, ARG2, ARG3, ARG4, ARG5> getResult(method: Function5<OUT, T, ARG1, ARG2, ARG3, ARG4, ARG5>): OUT? {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .find { it.name == factName }
            ?.let { fact -> client.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname) as OUT }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6> getResult(method: Function6<OUT, T, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6>): OUT? {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .find { it.name == factName }
            ?.let { fact -> client.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname) as OUT }
    }

    @Suppress("UNCHECKED_CAST")
    fun <OUT: Message, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7> getResult(method: Function7<OUT, T, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7>): OUT? {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .find { it.name == factName }
            ?.let { fact -> client.loadProto(fact.dataLocation.ref.hash, fact.dataLocation.classname) as OUT }
    }

    fun <OUT: Message> hasResult(method: Function<OUT, T>): Boolean {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .any { it.name == factName }
    }

    fun <OUT: Message, ARG1> hasResult(method: Function1<OUT, T, ARG1>): Boolean {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .any { it.name == factName }
    }

    fun <OUT: Message, ARG1, ARG2> hasResult(method: Function2<OUT, T, ARG1, ARG2>): Boolean {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .any { it.name == factName }
    }

    fun <OUT: Message, ARG1, ARG2, ARG3> hasResult(method: Function3<OUT, T, ARG1, ARG2, ARG3>): Boolean {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .any { it.name == factName }
    }

    fun <OUT: Message, ARG1, ARG2, ARG3, ARG4> hasResult(method: Function4<OUT, T, ARG1, ARG2, ARG3, ARG4>): Boolean {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .any { it.name == factName }
    }

    fun <OUT: Message, ARG1, ARG2, ARG3, ARG4, ARG5> hasResult(method: Function5<OUT, T, ARG1, ARG2, ARG3, ARG4, ARG5>): Boolean {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .any { it.name == factName }
    }

    fun <OUT: Message, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6> hasResult(method: Function6<OUT, T, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6>): Boolean {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .any { it.name == factName }
    }

    fun <OUT: Message, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7> hasResult(method: Function7<OUT, T, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7>): Boolean {
        val factName = MethodUtil.getMethodFact(contractClazz, method)

        return envelope.contract
            .inputsList
            .any { it.name == factName }
    }

    data class ScopeFact(
        val scopeUuid: UUID,
        val name: String
    )

    fun setFact(scopeFact: ScopeFact, name: String) {
        val scope = contractManager.indexClient.findLatestScopeByUuid(scopeFact.scopeUuid)
            .orThrowNotFound("Scope not found for ${scopeFact.scopeUuid}")
            .scope

        spec.inputSpecsList
            .filter { it.name == name }
            .takeIf { it.size == 1 }
            .orThrowNotFound("Can't find the fact for $name")

        val fact = scope.toFactMessage(scopeFact.name)

        val ref = ProvenanceReference.newBuilder()
            .setScopeUuid(scopeFact.scopeUuid.toProtoUuidProv())
            .setHash(scope.findFactRecord(scopeFact.name).resultHash)
            .build()

        stagedCrossScopeFacts[name] = ref to fact
    }

    data class ScopeFacts(
        val scopeUuids: List<UUID>,
        val name: String
    )

    fun setFact(scopeFacts: ScopeFacts, name: String) {
        val scopes = contractManager.indexClient.findLatestScopesByUuids(scopeFacts.scopeUuids)
            .scopesList
            .map { it.scope }

        val missingScopes = scopeFacts.scopeUuids.toSet().minus(scopes.map { it.uuid.toUuidProv() }.toSet())
        if (missingScopes.isNotEmpty()) {
            throw ContractDefinitionException("Unable to find scopes to set facts: ${missingScopes.map { it.toString() }.joinToString(", ")}")
        }

        val facts = scopes.map { scope ->
            val ref = ProvenanceReference.newBuilder()
                .setScopeUuid(scope.uuid)
                .setHash(scope.findFactRecord(scopeFacts.name).resultHash)
                .build()

            ref to scope.toFactMessage(scopeFacts.name)
        }

        stagedCrossScopeCollectionFacts[name] = facts
    }

    private fun Scope.toFactMessage(factName: String): Message = findFactRecord(factName).let { record ->
        client.loadProto(record.resultHash, record.classname)
    }

    private fun Scope.findFactRecord(factName: String): Record = recordGroupList
        .flatMap { it.recordsList }
        .find { it.resultName == factName }
        .orThrowNotFound("Can't find fact $factName in scope ${uuid.value}")

    fun addProposedFact(name: String, msg: Message) {
        // Complete list of proposed facts.
        val proposedSpec = listOf(
            spec.conditionSpecsList
                .flatMap { it.inputSpecsList }
                .filter { it.type == PROPOSED },
            spec.considerationSpecsList
                .flatMap { it.inputSpecsList }
                .filter { it.type == PROPOSED }
        )
            .flatten()
            .firstOrNull { it.name == name }
            .orThrowNotFound("Can't find the proposed fact for $name")

        require(proposedSpec.resourceLocation.classname == msg.defaultInstanceForType.javaClass.name)
               { "Invalid proto message supplied for $name. Expected: ${proposedSpec.resourceLocation.classname} Received: ${msg.defaultInstanceForType.javaClass.name}" }

        stagedProposedFacts[name] = proposedSpec to msg
    }

    @Synchronized private fun satisfyParticipant(partyType: PartyType, recital: RecitalData) {
        val recitalSpec = spec.partiesInvolvedList
            .addImplicitParties()
            .filter { it == partyType }
            .firstOrNull()
            .orThrowNotFound("Can't find participant for party type ${partyType}")

        val recitalDescriptor = when (recital) {
            is RecitalAddress -> recital.address.toByteString().toString(Charsets.UTF_8)
            is RecitalPublicKey -> recital.key.toHex()
        }

        stagedRecital.firstOrNull { it.first == recitalSpec }
            ?.let {
                throw ContractDefinitionException("Participant for party type $partyType was already satisfied with public key: $recitalDescriptor")
            }

        stagedRecital.add(recitalSpec to recital)
    }

    /**
     * Satisfy the requirement to have a signer or address for each recital in the interface of an P8eContract
     */
    fun satisfyParticipant(partyType: PartyType, publicKey: PublicKey) {
        if (addressPartyTypes.contains(partyType)) {
            throw ContractDefinitionException("Participant for party type $partyType is not a valid signer type")
        }

        satisfyParticipant(partyType, RecitalPublicKey(publicKey))
    }

    /**
     * Satisfy the requirement to have a signer or address for each recital in the interface of an P8eContract
     */
    fun satisfyParticipant(partyType: PartyType, address: Address) {
        if (!addressPartyTypes.contains(partyType)) {
            throw ContractDefinitionException("Participant for party type $partyType is not a valid address type")
        }

        val byteArray = when (address.type!!) {
            AddressType.BECH32 -> Bech32.decode(address.value).data
            AddressType.NO_ADDRESS_TYPE, AddressType.UNRECOGNIZED -> throw ContractDefinitionException("Invalid address type value ${address.type.number}")
        }

        satisfyParticipant(partyType, RecitalAddress(byteArray))
    }

    fun getStagedUuid(): UUID = envelope.ref.scopeUuid.toUuidProv()
    fun getStagedFacts() = stagedFacts.toList()
    fun getStagedProposedFacts() = stagedProposedFacts.toList()
    fun getStagedRecital() = stagedRecital.toList()
    fun getStagedContract() = stagedContract
    fun getStagedExecutionUuid() = stagedExecutionUuid

    private fun packageContract(): Envelope {
        if (executed.get(true))
            return this.executionEnvelope

        this.stagedContract = populateContract()

        val permissionUpdater = PermissionUpdater(
                contractManager,
                this.stagedContract,
                this.stagedContract.toAudience(envelope.scope)
        )

        permissionUpdater.saveConstructorArguments()

        // Build the envelope for this execution
        this.executionEnvelope = envelope.toBuilder()
            .setExecutionUuid(this.stagedExecutionUuid)
            .setContract(this.stagedContract)
            .also { builder ->
                stagedPrevExecutionUuid?.run { builder.prevExecutionUuid = this }
                stagedExpirationTime?.run { builder.expirationTime = toProtoTimestampProv() } ?: builder.clearExpirationTime()
                builder.ref = builder.refBuilder
                    .setHash(this.stagedContract.toByteArray().base64Sha512())
                    .build()
            }
            .clearSignatures()
            .build()

        executed.set(true)

        // Save spec in case its not loaded
        // TODO This probably should be removed since we can't on the fly install specs.
        saveSpec(contractManager)

        permissionUpdater.saveProposedFacts(this.stagedExecutionUuid.toUuidProv(), this.stagedProposedProtos)

        return this.executionEnvelope
    }

    fun saveSpec(contractManager: ContractManager) {
        contractManager.saveProto(spec, audience = stagedContract.toAudience(envelope.scope))
    }

    fun isCompleted(): Boolean {
        val specOutputSize = listOf(
            spec.considerationSpecsList.filter { it.hasOutputSpec() }.size,
            spec.conditionSpecsList.filter { it.hasOutputSpec() }.size
        ).reduceRight { a, b -> a + b }

        val contractOutputSize = listOf(
            envelope.contract.considerationsList.filter { it.hasResult() && it.result.hasOutput() }.size,
            envelope.contract.conditionsList.filter { it.hasResult() && it.result.hasOutput() }.size
        ).reduceRight {a, b -> a + b }

        return specOutputSize == contractOutputSize
    }

    /**
     * Only allow a max recursion depth of 10 for now...
     */
    fun isDead(): Boolean {
        return envelope.contract.timesExecuted > 10
    }

    private fun populateContract(): ProtoContract {
        val builder = envelope.contract.toBuilder()

        builder.invoker = PK.SigningAndEncryptionPublicKeys.newBuilder()
            .setEncryptionPublicKey(contractManager.keyPair.public.toPublicKeyProto())
            .setSigningPublicKey(contractManager.keyPair.public.toPublicKeyProto())
            .build()

        // Copy the outputs from previous contract executions to the inputs list.
        spec.conditionSpecsList
            .filter { it.hasOutputSpec() }
            .map { it.outputSpec }
            .map { defSpec ->
                envelope.contract.conditionsList
                    .filter { it.hasResult() }
                    .filter { it.result.output.name == defSpec.spec.name }
                    .map { it.result.output }
                    .singleOrNull()
                    // TODO warn if more than one output with same name.
                    ?.let {
                        // Only add the output to the input list if it hasn't been previously defined.
                        if (builder.inputsList.find { fact -> fact.name == it.name } == null) {
                            builder.addInputs(
                                Fact.newBuilder()
                                    .setName(it.name)
                                    .setDataLocation(
                                        Location.newBuilder()
                                            .setClassname(it.classname)
                                            .setRef(
                                                ProvenanceReference.newBuilder()
                                                    .setHash(it.hash)
                                                    .setGroupUuid(envelope.ref.groupUuid)
                                                    .setScopeUuid(envelope.ref.scopeUuid)
                                                    .build()
                                            )
                                    )
                            )
                        }
                    }
            }

        spec.considerationSpecsList
            .filter { it.hasOutputSpec() }
            .map { it.outputSpec }
            .map { defSpec ->
                envelope.contract.considerationsList
                    .filter { it.hasResult() }
                    .filter { it.result.output.name == defSpec.spec.name }
                    .map { it.result.output }
                    .singleOrNull()
                    // TODO warn if more than one output with same name.
                    ?.let {
                        // Only add the output to the input list if it hasn't been previously defined.
                        if (builder.inputsList.find { fact -> fact.name == it.name } == null) {
                            builder.addInputs(
                                Fact.newBuilder()
                                    .setName(it.name)
                                    .setDataLocation(
                                        Location.newBuilder()
                                            .setClassname(it.classname)
                                            .setRef(
                                                ProvenanceReference.newBuilder()
                                                    .setHash(it.hash)
                                                    .setGroupUuid(envelope.ref.groupUuid)
                                                    .setScopeUuid(envelope.ref.scopeUuid)
                                                    .build()
                                            )
                                    )
                            )
                        }
                    }
            }

        // All facts should already be loaded to the system. No need to send them to POS.
        stagedFacts.forEach {
            val msg = it.value.second.second
            val ref = it.value.second.first.takeUnless { ref -> ref == ProvenanceReference.getDefaultInstance() }
                ?: ProvenanceReference.newBuilder().setHash(msg.toByteArray().base64Sha512()).build()

            builder.addInputs(
                Fact.newBuilder()
                    .setName(it.key)
                    .setDataLocation(
                        Location.newBuilder()
                            .setClassname(msg.javaClass.name)
                            .setRef(ref)
                    )
            )
        }

        stagedCrossScopeFacts.forEach { (factName, refMessage) ->
            val (ref, message) = refMessage

            builder.populateFact(
                Fact.newBuilder()
                    .setName(factName)
                    .setDataLocation(
                        Location.newBuilder()
                            .setClassname(message.javaClass.name)
                            .setRef(ref)
                    ).build()
            )
        }

        stagedCrossScopeCollectionFacts.forEach { (factName, collection) ->
            collection.forEach { (ref, message) ->
                builder.populateFact(
                    Fact.newBuilder()
                        .setName(factName)
                        .setDataLocation(
                            Location.newBuilder()
                                .setClassname(message.javaClass.name)
                                .setRef(ref)
                        ).build()
                )
            }
        }

        spec.considerationSpecsList
            .filter { it.inputSpecsList.find { it.type == PROPOSED } != null }
            .forEach { considerationSpec ->
                // Find the consideration impl for the spec.
                val consideration = builder.considerationsBuilderList
                    .filter { it.considerationName == considerationSpec.funcName }
                    .single()
                    .orThrowNotFound("Function not found for ${considerationSpec.funcName}")

                considerationSpec.inputSpecsList.forEach { defSpec ->
                    // Search the Function for an input that hasn't been previously satisfied
                    if (consideration.inputsList.find { it.name == defSpec.name } == null) {
                        stagedProposedFacts[defSpec.name]?.also {
                            consideration.addInputs(
                                ProposedFact.newBuilder()
                                    .setClassname(defSpec.resourceLocation.classname)
                                    .setHash(it.second.toByteArray().base64Sha512())
                                    .setName(defSpec.name)
                                    .build()
                            ).also {
                                // Clear any previous skip result if there is a proposed fact for the consideration.
                                if(it.result.resultValue == ExecutionResult.Result.SKIP_VALUE) {
                                    it.clearResult()
                                }
                            }

                            // Prepare for upload
                            if (!stagedProposedProtos.contains(it.second))
                                stagedProposedProtos.add(it.second)
                        }
                    }
                }
            }

        val scope = envelope.scope.takeUnless { it == Scope.getDefaultInstance() }
        if (scope != null) {
            scope.recordGroupList
                .flatMap { it.recordsList }
                .associateBy { it.resultName }
                .forEach { (factName, scopeFact) ->
                    builder.populateFact(
                        Fact.newBuilder()
                            .setName(factName)
                            .setDataLocation(
                                Location.newBuilder()
                                    .setClassname(scopeFact.classname)
                                    .setRef(
                                        ProvenanceReference.newBuilder()
                                            .setScopeUuid(scope.uuid)
                                            .setHash(scopeFact.resultHash)
                                    )
                            ).build()
                    )
                }
        }

        val formattedStagedRecitals = stagedRecital.map { (partyType, data) ->
            Recital.newBuilder()
                .setSignerRole(partyType)
                .also { recitalBuilder ->
                    when (data) {
                        is RecitalAddress -> recitalBuilder
                            .setAddress(data.address.toByteString())
                        is RecitalPublicKey -> recitalBuilder
                            .setSigner(
                                // Setting single key for both Signing and Encryption key fields, service will handle updating key fields.
                                PK.SigningAndEncryptionPublicKeys.newBuilder()
                                    .setSigningPublicKey(data.key.toPublicKeyProto())
                                    .setEncryptionPublicKey(data.key.toPublicKeyProto())
                                    .build()
                            )
                    } as Recital.Builder
                }
                .build()
        }

        builder.clearRecitals()
        if (scope != null) {
            builder.addAllRecitals(formattedStagedRecitals.plus(scope.partiesList).distinctBy { it.signerRole })
        } else {
            builder.addAllRecitals(formattedStagedRecitals)
        }

        builder.timesExecuted ++

        return builder.build()
    }

    fun execute(): Either<P8eError, Contract<T>> = try {
        if(this.isCompleted())
            throw IllegalArgumentException("Contract has already been completed.")

        if(this.isDead())
            throw IllegalArgumentException("Contract reached max recursion")

        if (this.isFragment) {
            // TODO can we remove this?
            timed("contract_saveSpec") {
                this.saveSpec(contractManager)
            }

            execute(envelope.executionUuid.toUuidProv())
        } else {
            execute(this.packageContract())
        }
    } catch (t: Throwable) {
        Either.left(P8eError.PreExecutionError(t))
    }

    private fun execute(
            executionUuid: UUID
    ): Either<P8eError, Contract<T>> {
        val event = newEventBuilder(this.contractClazz.name, contractManager.keyPair.public)
            .setAction(Action.EXECUTE_FRAGMENT)
            .setEnvelope(Envelope.newBuilder().setExecutionUuid(executionUuid.toProtoUuidProv()).build())
            .build()

        return executor(event)
    }

    private fun execute(
        envelope: Envelope
    ): Either<P8eError, Contract<T>> {
        val event = newEventBuilder(this.contractClazz.name, contractManager.keyPair.public)
                .setAction(Action.EXECUTE)
                .setEnvelope(envelope)
                .build()

        return executor(event)
    }

    private fun newEventBuilder(className: String, publicKey: PublicKey): EnvelopeEvent.Builder {
        return EnvelopeEvent.newBuilder()
            .setClassname(className)
            .setPublicKey(
                PK.SigningAndEncryptionPublicKeys.newBuilder()
                    .setSigningPublicKey(publicKey.toPublicKeyProto())
            )
    }

    private fun Contracts.Contract.Builder.populateFact(fact: Fact) {
        inputsBuilderList.firstOrNull {
            isMatchingFact(it, fact.name)
        }?.apply {
            dataLocation = fact.dataLocation
        }
    }

    private fun Contracts.Contract.toAudience(scope: Scope): Set<PublicKey> {
        return recitalsList.plus(scope.partiesList)
            .filter { it.hasSigner() }
            .map { it.signer.encryptionPublicKey.publicKeyBytes }
            .filterNot { it.isEmpty }
            .map { ECUtils.convertBytesToPublicKey(it.toByteArray()) }
            .toSet()
    }

    private fun isMatchingFact(inputFact: FactOrBuilder, factName: String): Boolean {
        return inputFact.name == factName && inputFact.dataLocation.ref == ProvenanceReference.getDefaultInstance()
    }
}
