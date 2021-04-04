package io.p8e

import arrow.core.Either
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import com.google.protobuf.Timestamp
import com.google.protobuf.util.Timestamps
import io.grpc.ManagedChannel
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.netty.channel.ChannelOption
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.p8e.async.EnvelopeEventObserver
import io.p8e.client.FactSnapshot
import io.p8e.client.P8eClient
import io.p8e.client.RemoteClient
import io.p8e.contracts.ContractHash
import io.p8e.exception.P8eError
import io.p8e.exception.p8eError
import io.p8e.functional.*
import io.p8e.grpc.Constant
import io.p8e.grpc.client.AuthenticationClient
import io.p8e.grpc.client.ChallengeResponseInterceptor
import io.p8e.grpc.observers.HeartbeatConnectionKey
import io.p8e.grpc.observers.HeartbeatQueuer
import io.p8e.grpc.observers.HeartbeatRunnable
import io.p8e.grpc.observers.QueueingStreamObserverSender
import io.p8e.index.client.IndexClient
import io.p8e.proto.*
import io.p8e.proto.Common.DefinitionSpec.Type.FACT
import io.p8e.proto.Common.Location
import io.p8e.proto.Common.ProvenanceReference
import io.p8e.proto.ContractScope.*
import io.p8e.proto.ContractScope.Envelope
import io.p8e.proto.ContractScope.Envelope.Status
import io.p8e.proto.ContractSpecs.ContractSpec
import io.p8e.proto.ContractSpecs.PartyType
import io.p8e.proto.Envelope.EnvelopeEvent
import io.p8e.proto.Envelope.EnvelopeEvent.EventType
import io.p8e.proxy.Contract
import io.p8e.proxy.ContractError
import io.p8e.spec.ContractSpecMapper
import io.p8e.spec.ContractSpecMapper.newContract
import io.p8e.spec.P8eContract
import io.p8e.util.*
import io.p8e.util.configureProvenance
import io.provenance.p8e.shared.extension.logger
import org.apache.commons.logging.LogFactory
import org.apache.commons.logging.impl.Log4JLogger
import java.io.Closeable
import java.io.File
import java.net.URI
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.time.OffsetDateTime
import java.util.ServiceLoader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

class ContractManager(
    val client: P8eClient,
    val indexClient: IndexClient,
    val keyPair: KeyPair
): Closeable {
    companion object {
        private val watchLock = Object()
        private val objectMapper = ObjectMapper().configureProvenance()
        private var channel: ManagedChannel? = null

        fun create(hexKey: String, url: String? = null) = create(hexKey.toJavaPrivateKey(), url)

        fun create(privateKey: PrivateKey, url: String? = null): ContractManager {
            val keyPair = privateKey.let {
                KeyPair(it.computePublicKey(), it)
            }

            return create(keyPair, url)
        }

        /**
         * Create a new ContractManager for a given Party
         */
        fun create(keyPair: KeyPair, url: String? = null): ContractManager {
            val apiUrl = url ?: System.getenv("API_URL") ?: "http://localhost:8080/engine"
            val uri = URI(apiUrl)
            val customTrustStore = System.getenv("TRUST_STORE_PATH")?.let(::File)

            channel = NettyChannelBuilder.forAddress(uri.host, uri.port)
                    .also {
                        if (uri.scheme == "grpcs") {
                            it.useTransportSecurity()
                            if (customTrustStore != null) {
                                val context =
                                    GrpcSslContexts.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                                        .build()
                                when (it) {
                                    is NettyChannelBuilder -> it.sslContext(context)
                                }
                            }
                        } else {
                            it.usePlaintext()
                        }
                    }.maxInboundMessageSize(Constant.MAX_MESSAGE_SIZE) // Set max inbound to 200MBB
                    .executor(ForkJoinPool.commonPool())
                    .idleTimeout(5, TimeUnit.MINUTES)
                    .keepAliveTime(60, TimeUnit.SECONDS)
                    .keepAliveTimeout(20, TimeUnit.SECONDS)
                    .initialFlowControlWindow(NettyServerBuilder.DEFAULT_FLOW_CONTROL_WINDOW * 32)
                    .withOption(ChannelOption.SO_SNDBUF, 1024 * 1024 * 16)
                    .withOption(ChannelOption.SO_RCVBUF, 1024 * 1024 * 16)
                    .build()


            val interceptor = ChallengeResponseInterceptor(
                keyPair,
                AuthenticationClient(
                    channel!!
                )
            )

            val index = IndexClient(channel!!, interceptor, objectMapper)
            return RemoteClient(keyPair.public, channel!!, interceptor, index).let {
                ContractManager(it, index, keyPair)
            }
        }
    }

    private val factPojoHydrator = FactPojoHydrator(client)
    private val contractHashes = ServiceLoader.load(ContractHash::class.java).toList()
    private val protoHashes = ServiceLoader.load(ProtoHash::class.java).toList()
    private val queuers = ConcurrentHashMap<Class<out P8eContract>, QueueingStreamObserverSender<EnvelopeEvent>>()
    private val handlers = ConcurrentHashMap<Class<out P8eContract>, ContractHandlers<out P8eContract>>()
    private val publicKey = keyPair.public

    data class ContractHandlers<T: P8eContract>(
        val requestHandler: ContractEventHandler<T>,
        val stepCompletionHandler: ContractEventHandler<T>,
        val errorHandler: ContractErrorHandler<T>
    )

    private val heartbeatConnections = ConcurrentHashMap<HeartbeatConnectionKey, HeartbeatQueuer>()
    private val heartbeatExecutor = ThreadPoolFactory.newScheduledThreadPool(1, "heartbeat-%d")

    init {
        val logger = LogFactory.getLog("org.apache.http.wire")
        when (logger) {
            is Log4JLogger -> logger.logger.level = org.apache.log4j.Level.INFO
            is Logger      -> logger.level = Level.INFO
        }
        contractHashes.takeIf { it.isEmpty() }?.apply { ContractManager.logger().error("Contract Hashes is empty, this should not happen!!!") }
        protoHashes.takeIf { it.isEmpty() }?.apply { ContractManager.logger().error("Proto Hashes is empty, this should not happen!!!") }

        heartbeatExecutor.scheduleAtFixedRate(
            HeartbeatRunnable(heartbeatConnections),
            1,
            1,
            TimeUnit.SECONDS
        )
    }

    private fun <T: P8eContract> getContractHash(clazz: Class<T>): ContractHash {
        return contractHashes.find {
            it.getClasses()[clazz.name] == true
        }.orThrow { IllegalStateException("Unable to find ContractHash instance to match ${clazz.name}, please verify you are running a Provenance bootstrapped JAR.") }
    }

    private fun getProtoHash(contractHash: ContractHash, clazz: Class<*>): ProtoHash {
        return protoHashes.find {
            it.getUuid() == contractHash.getUuid() && it.getClasses()[clazz.name] == true
        }.orThrow { IllegalStateException("Unable to find ProtoHash instance to match ${clazz.name}, please verify you are running a Provenance bootstrapped JAR.") }
    }

    private fun <T: P8eContract> newContractProto(contractClazz: Class<T>): Contracts.Contract.Builder =
        dehydrateSpec(contractClazz).newContract()
            .setDefinition(
                ProtoUtil.defSpecBuilderOf(
                    contractClazz.name,
                    ProtoUtil.locationBuilderOf(
                        contractClazz.name,
                        ProvenanceReference.newBuilder().setHash(getContractHash(contractClazz).getHash()).build()
                    ),
                    FACT
                ).build()
            )

    /**
     * Returns a new Contract object that can be configured and executed. This should only be called for creating a Contract
     * against a brand new Scope.
     *
     * @param contractClazz subclass of P8eContract that represents this contract
     * @param scopeUuid the scope uuid to set for this contract
     * @param executionUuid the execution uuid to set for this contract execution
     * @param invokerRole the PartyType to satisfy for this ContractManager's public key
     */
    fun <T: P8eContract> newContract(contractClazz: Class<T>, scopeUuid: UUID, executionUuid: UUID? = null, invokerRole: PartyType? = null): Contract<T> {
        val scope = Scope.newBuilder()
            .setUuid(scopeUuid.toProtoUuidProv())
            .build()

        return newContract(contractClazz, scope, executionUuid, invokerRole)
    }

    /**
     * Returns a new Contract object that can be configured and executed. This should be called for creation of all Contract's
     * against an existing Scope.
     *
     * @param contractClazz subclass of P8eContract that represents this contract
     * @param scope the Scope to set for this contract
     * @param executionUuid the execution uuid to set for this contract execution
     * @param invokerRole the PartyType to satisfy for this ContractManager's public key
     */
    fun <T: P8eContract> newContract(contractClazz: Class<T>, scope: Scope, executionUuid: UUID? = null, invokerRole: PartyType? = null): Contract<T> {
        val env = Envelope.newBuilder()
            .setContract(newContractProto(contractClazz).build())
            .setExecutionUuid(executionUuid.or { UUID.randomUUID() }.toProtoUuidProv())
            .setScope(scope)
            .setRef(ProvenanceReference.newBuilder()
                .setScopeUuid(scope.uuid)
                .setGroupUuid(UUID.randomUUID().toProtoUuidProv())
            )

        return Contract(
            this,
            client,
            dehydrateSpec(contractClazz),
            env.build(),
            contractClazz,
            contractClassExecutor(contractClazz)
        ).also { contract ->
            invokerRole?.let { contract.satisfyParticipant(it, publicKey) }
        }
    }

    /**
     * <strong>EXPERIMENTAL</strong>
     *
     * Returns a new Contract object that can be configured and executed. This Contract represents a contract
     * based on a previously executed Contract that did not complete successfully. The envelope should contain
     * a different execution uuid than was originally associated with the Envelope when it failed execution.
     *
     * @param contractClazz subclass of P8eContract that represents this contract
     * @param envelope the Envelope to set for this contract
     */
    fun <T: P8eContract> newContract(contractClazz: Class<T>, envelope: Envelope): Contract<T> {
        val contract = envelope.contract

        // Validate that the contract is intact enough to build something that can be executed.
        require(contract.definition.resourceLocation.classname.isNotEmpty()) {
            "Contract is missing [definition.resourceLocation.classname]"
        }

        val classname = contract.definition.resourceLocation.classname
        require(classname == contractClazz.name) {
            "Contract classname ${classname} doesn't match cast type of ${contractClazz.name}"
        }

        return Contract(
            this,
            client,
            dehydrateSpec(contractClazz),
            envelope,
            contractClazz,
            contractClassExecutor(contractClazz)
        )
    }

    /**
     * Returns a new Contract object that can be configured and executed. This Contract represents a change in ownership
     * of the Scope.
     *
     * @param contractClazz subclass of P8eContract that represents this contract
     * @param scope the Scope to set for this contract
     * @param executionUuid the execution uuid to set for this contract execution
     * @param invokerRole the PartyType to satisfy for this ContractManager's public key
     */
    fun <T: P8eContract> changeScopeOwnership(contractClazz: Class<T>, scope: Scope, executionUuid: UUID? = null, invokerRole: PartyType? = null): Contract<T> {
        val contractProto = newContractProto(contractClazz)
            .setType(Contracts.ContractType.CHANGE_SCOPE)
            .build()
        val env = Envelope.newBuilder()
            .setContract(contractProto)
            .setExecutionUuid(executionUuid.or { UUID.randomUUID() }.toProtoUuidProv())
            .setScope(scope)
            .setRef(ProvenanceReference.newBuilder().setScopeUuid(scope.uuid).setGroupUuid(randomProtoUuidProv()).build())
            .build()

            return Contract(
                this,
                client,
                dehydrateSpec(contractClazz),
                env,
                contractClazz,
                contractClassExecutor(contractClazz)
            ).also { contract ->
                invokerRole?.let { contract.satisfyParticipant(it, publicKey) }
            }
    }

    fun <T: P8eContract> loadContract(
        clazz: Class<T>,
        executionUuid: UUID
    ): Contract<T> {
        return Contract(
            this,
            client,
            dehydrateSpec(clazz),
            client.getContract(executionUuid),
            clazz,
            contractClassExecutor(clazz),
            false
        ).also {
            it.newExecution()
        }
    }

    fun <T: P8eContract> dehydrateSpec(clazz: Class<T>): ContractSpec {
        val contractHash = getContractHash(clazz)
        val protoHash = clazz.methods
            .find { it.returnType != null && Message::class.java.isAssignableFrom(it.returnType) }
            ?.returnType
            ?.let { getProtoHash(contractHash, it) }
            .orThrow {
                IllegalStateException("Unable to find hash for proto JAR for return types on ${clazz.name}")
            }
        val contractRef = ProvenanceReference.newBuilder().setHash(contractHash.getHash()).build()
        val protoRef = ProvenanceReference.newBuilder().setHash(protoHash.getHash()).build()

        return ContractSpecMapper.dehydrateSpec(clazz.kotlin, contractRef, protoRef)
    }

    fun <T: P8eContract> status(contract: Contract<T>): Status {
        return status(contract.envelope.executionUuid.toUuidProv())
    }

    fun status(executionUuid: UUID): Status {
        return client.getContract(executionUuid)
            .status
    }

    fun <T: P8eContract> cancel(contract: Contract<T>, message: String = "") {
        cancel(contract.envelope.executionUuid.toUuidProv(), message)
    }

    fun cancel(executionUuid: UUID, message: String = "") {
        client.cancel(executionUuid, message)
    }

    fun <T: P8eContract> reject(contract: Contract<T>, message: String = "") {
        reject(contract.envelope.executionUuid.toUuidProv(), message)
    }

    fun reject(executionUuid: UUID, message: String = "") {
        client.reject(executionUuid, message)
    }

    private fun newEventBuilder(className: String, publicKey: PublicKey): EnvelopeEvent.Builder {
        return EnvelopeEvent.newBuilder()
            .setClassname(className)
            .setPublicKey(
                PK.SigningAndEncryptionPublicKeys.newBuilder()
                    .setSigningPublicKey(publicKey.toPublicKeyProto())
            )
    }

    private fun <T> Class<T>.toProto() = Affiliate.AffiliateContractWhitelist.newBuilder().setClassname(name).build()

    class WatchBuilder<T: P8eContract>(
        private val publicKey: PublicKey,
        private val clazz: Class<T>,
        private val contractManager: ContractManager
    ) {
        private var requestHandler: ContractEventHandler<T> = { contract: Contract<T> ->
            logger().info("Received contract request on public key ${publicKey.toHex()} for class ${contract.contractClazz.name} with Execution UUID: ${contract.envelope.executionUuid.value}")
            true
        }.toContractEventHandler()

        private var stepCompletionHandler: ContractEventHandler<T>  = { contract: Contract<T> ->
            logger().info("Received contract response on public key ${publicKey.toHex()} for class ${contract.contractClazz.name} with Scope UUID: ${contract.getScopeUuid()}")
            true
        }.toContractEventHandler()

        private var errorHandler: ContractErrorHandler<T> = { contractError: ContractError<T> ->
            logger().error("Received contract error on public key ${publicKey.toHex()} for execution ${contractError.error.executionUuid.value} group ${contractError.error.groupUuid.value}\n${contractError.error.message}")
            true
        }.toContractErrorHandler()

        private var disconnectHandler: DisconnectHandler = { reconnectHandler: ReconnectHandler ->
            logger().info("Received disconnect for ${getInfo()}. Sleeping for 20 seconds and then reconnecting.")

            Thread.sleep(20_000)
            reconnectHandler.reconnect()
        }.toDisconnectHandler()

        fun request(requestHandler: ContractEventHandler<T>): WatchBuilder<T> {
            this.requestHandler = requestHandler
            return this
        }

        fun request(requestHandler: (Contract<T>) -> Boolean): WatchBuilder<T> {
            this.requestHandler = requestHandler.toContractEventHandler()
            return this
        }

        fun stepCompletion(stepCompletionHandler: ContractEventHandler<T>): WatchBuilder<T> {
            this.stepCompletionHandler = stepCompletionHandler
            return this
        }

        fun stepCompletion(stepCompletionHandler: (Contract<T>) -> Boolean): WatchBuilder<T> {
            this.stepCompletionHandler = stepCompletionHandler.toContractEventHandler()
            return this
        }

        fun error(errorHandler: ContractErrorHandler<T>): WatchBuilder<T> {
            this.errorHandler = errorHandler
            return this
        }

        fun error(errorHandler: (ContractError<T>) -> Boolean): WatchBuilder<T> {
            this.errorHandler = errorHandler.toContractErrorHandler()
            return this
        }

        fun disconnect(disconnectHandler: DisconnectHandler): WatchBuilder<T> {
            this.disconnectHandler = disconnectHandler
            return this
        }

        fun disconnect(disconnectHandler: (ReconnectHandler) -> Unit): WatchBuilder<T> {
            this.disconnectHandler = disconnectHandler.toDisconnectHandler()
            return this
        }

        fun executeRequests(): WatchBuilder<T> {
            this.requestHandler = { contract: Contract<T> ->
                logger().info("Received contract request on public key ${publicKey.toHex()} for class ${contract.contractClazz.name}")
                if (!contract.isCompleted()) {
                    contract.execute().isRight()
                } else {
                    true
                }
            }.toContractEventHandler()
            return this
        }

        fun watch() {
            contractManager.watch(
                clazz,
                requestHandler,
                stepCompletionHandler,
                errorHandler,
                DisconnectHandlerWrapper(
                    disconnectHandler,
                    this
                )
            )
        }

        fun getInfo(): String {
            return "Public Key [${publicKey.toHex()}] Contract Class [${clazz.name}]"
        }
    }

    fun <T: P8eContract> watchBuilder(clazz: Class<T>): WatchBuilder<T> {
        return WatchBuilder(publicKey, clazz, this)
    }

    fun <T: P8eContract> unwatch(clazz: Class<T>) {
        queuers[clazz]?.close()
        queuers.remove(clazz)
        heartbeatConnections.remove(HeartbeatConnectionKey(publicKey, clazz))
        handlers.remove(clazz)
    }

    private fun <T: P8eContract> watch(
        clazz: Class<T>,
        requestHandler: ContractEventHandler<T>,
        stepCompletionHandler: ContractEventHandler<T>,
        errorHandler: ContractErrorHandler<T>,
        disconnectHandler: DisconnectHandlerWrapper<T>
    ) = synchronized(watchLock) {
        if (queuers.containsKey(clazz) || handlers.containsKey(clazz)) {
            throw IllegalStateException("Unable to watch for class ${clazz.name} more than once.")
        }
        client.whitelistClass(clazz.toProto())

        handlers.computeIfAbsent(clazz) {
            ContractHandlers(requestHandler, stepCompletionHandler, errorHandler)
        }

        val heartbeatEvent = newEventBuilder(clazz.name, publicKey).setAction(EnvelopeEvent.Action.HEARTBEAT).build()
        val inObserver = EnvelopeEventObserver(clazz, this::event, disconnectHandler, { unwatch(clazz) }) { inObserver ->
            client.event(clazz, inObserver)
                .also { outObserver ->
                    val queuer = QueueingStreamObserverSender(outObserver)
                    val envObserver = (inObserver as EnvelopeEventObserver<*>)
                    envObserver.queuer.getAndSet(queuer).close()
                    queuers[clazz] = queuer
                    heartbeatConnections[HeartbeatConnectionKey(publicKey, clazz)] = HeartbeatQueuer(heartbeatEvent, queuer)
                }
        }
        val queuer = queuers.computeIfAbsent(clazz) {
            QueueingStreamObserverSender(client.event(clazz, inObserver))
        }
        inObserver.queuer.set(queuer)
        heartbeatConnections[HeartbeatConnectionKey(publicKey, clazz)] = HeartbeatQueuer(heartbeatEvent, queuer)

        // Notify other executions that the watch has executed.
        watchLock.notify()
    }

    private fun <T: P8eContract> event(
        clazz: Class<T>,
        event: EnvelopeEvent
    ) {
        if (event.action == EnvelopeEvent.Action.HEARTBEAT) {
            return
        }
        val classHandlers = handlers[clazz]
            .orThrow { IllegalStateException("Handlers not registered for ${clazz.name}") }

        val response = when (event.event) {
            EventType.ENVELOPE_ACCEPTED -> false
            EventType.ENVELOPE_RESPONSE -> try {
                classHandlers.stepCompletionHandler.cast<T>().handle(constructContract(clazz, event))
            } catch (t: Throwable) {
                logger().error("Error during step completion handler: ", t)
                throw t
            }
            EventType.ENVELOPE_REQUEST -> try {
                classHandlers.requestHandler.cast<T>().handle(constructContract(clazz, event))
            } catch (t: Throwable) {
                logger().error("Error during request handler: ", t)
                throw t
            }
            EventType.ENVELOPE_ERROR -> try {
                val contractError = ContractError(
                    contractClazz = clazz,
                    event = event,
                    error = event.error,
                )
                classHandlers.errorHandler.cast<T>().handle(contractError)
            } catch (t: Throwable) {
                logger().error("Error during error handler: ", t)
                throw t
            }
            else -> throw IllegalStateException("Unknown event type ${event.event.name}")
        }

        if (response) {
            queuers[clazz]?.queue(event.toBuilder().setAction(EnvelopeEvent.Action.ACK).build())
        }
    }

    fun <T: P8eContract> ackProcessedEvent(contract: Contract<T>) {
        contract.constructedFromEvent
            .takeIf { it != null }
            ?.let { ackProcessedEvent(contract.contractClazz, it) }
    }

    fun <T: P8eContract> ackProcessedEvent(contractError: ContractError<T>) {
        ackProcessedEvent(contractError.contractClazz, contractError.event)
    }

    private fun <T: P8eContract> ackProcessedEvent(contractClazz: Class<T>, event: EnvelopeEvent) {
        if (event.action == EnvelopeEvent.Action.HEARTBEAT) {
            return
        }

        queuers[contractClazz]?.queue(event.toBuilder().setAction(EnvelopeEvent.Action.ACK).build())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T: P8eContract> ContractEventHandler<out P8eContract>.cast(): ContractEventHandler<T> {
        return this as ContractEventHandler<T>
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T: P8eContract> ContractErrorHandler<out P8eContract>.cast(): ContractErrorHandler<T> {
        return this as ContractErrorHandler<T>
    }

    private fun <T: P8eContract> constructContract(
        clazz: Class<T>,
        event: EnvelopeEvent
    ): Contract<T> {
        return Contract(
            this,
            client,
            dehydrateSpec(clazz),
            event.envelope,
            clazz,
            contractClassExecutor(clazz),
            when (event.event) {
                EventType.ENVELOPE_REQUEST -> true
                EventType.ENVELOPE_RESPONSE -> false
                EventType.ENVELOPE_ACCEPTED -> false
                else -> throw IllegalStateException("Unable to handle event of type ${event.event.name} as REQUEST/RESPONSE")
            },
            event
        )
    }

    private fun <T: P8eContract> contractClassExecutor(clazz: Class<T>): (request: EnvelopeEvent) -> Either<P8eError, Contract<T>> = { request ->
        try {
            client.execute(request)
                .let { response ->
                    when (response.event) {
                        EventType.ENVELOPE_EXECUTION_ERROR, EventType.ENVELOPE_ERROR -> Either.left(response.error.p8eError())

                        EventType.ENVELOPE_ACCEPTED,
                        EventType.ENVELOPE_MAILBOX_OUTBOUND,
                        EventType.ENVELOPE_REQUEST,
                        EventType.ENVELOPE_RESPONSE -> Either.right(constructContract(clazz, response))

                        EventType.UNRECOGNIZED, EventType.UNUSED_TYPE, null -> throw IllegalStateException("Invalid EventType of ${response.event}")
                    }
                }
        } catch (t: Throwable) {
            Either.left(t.p8eError())
        }
    }

    // TODO - determine what we're doing with proto loading and retrieval since contract engine
    // requires the jar anyways before a contract spec can run so just a spec proto is insufficient.
    inline fun <reified T: Message> loadProto(uri: String): T = client.loadProto(uri, T::class.java)

    fun loadProto(uri: String, className: String) = client.loadProto(uri, Class.forName(className) as Class<Message>)

    fun saveProto(
        msg: Message,
        executionUuid: UUID? = null,
        audience: Set<PublicKey> = setOf()
    ): Location = client.saveProto(
        msg,
        executionUuid,
        audience = audience
    )

    inline fun <reified T: Message> getFactHistory(
        scopeUuid: UUID,
        factName: String,
        startWindow: OffsetDateTime = Timestamps.MIN_VALUE.toOffsetDateTimeProv(),
        endWindow: OffsetDateTime = Timestamps.MAX_VALUE.toOffsetDateTimeProv()
    ): List<FactSnapshot<T>> {
        return getFactHistory(
            scopeUuid,
            factName,
            T::class.java,
            startWindow,
            endWindow
        )
    }

    /**
     * Retrieve a list of facts for a given fact name sorted by when it was created desc.
     * Optional configuration to allow fetching fact history within a given window of time.
     */
    fun <T: Message> getFactHistory(
        scopeUuid: UUID,
        factName: String,
        clazz: Class<T>,
        startWindow: OffsetDateTime = Timestamps.MIN_VALUE.toOffsetDateTimeProv(),
        endWindow: OffsetDateTime = Timestamps.MAX_VALUE.toOffsetDateTimeProv()
    ): List<FactSnapshot<T>> {
        return client.getFactHistory(
            scopeUuid,
            factName,
            clazz.name,
            startWindow,
            endWindow
        ).entriesList
            .map { entry ->
                val updatedTimestamp =  entry.factAuditFields
                    .updatedDate
                    .takeIf { it != Timestamp.getDefaultInstance() }
                    ?: entry.factAuditFields.createdDate

                val fact = client.loadProto(
                    entry.factBytes.toByteArray(),
                    clazz.name
                ).let { message ->
                    when {
                        clazz.isAssignableFrom(message.javaClass) -> clazz.cast(message)
                        else -> throw IllegalStateException("Unable to cast ${message.javaClass.name} as ${clazz.name}")
                    }
                }

                FactSnapshot(
                    entry.executor,
                    entry.partiesList,
                    entry.contractJarHash,
                    entry.contractClassname,
                    entry.functionName,
                    entry.resultName,
                    entry.resultHash,
                    fact,
                    updatedTimestamp.toOffsetDateTimeProv(),
                    entry.blockNumber,
                    entry.blockTransactionIndex
                )
            }.sortedByDescending { it.updated }
    }

    /**
     * Load a spec from POS.
     *
     * @return [ContractSpec] (proto).
     */
    fun loadContract(uri: String) = loadProto<Contracts.Contract>(uri)

    fun <T> hydrate(scopeUuid: UUID, clazz: Class<T>): T {
        val scope = indexClient.findLatestScopeByUuid(scopeUuid)
            .orThrow { IllegalStateException("Unable to find scope with uuid $scopeUuid") }
            .scope

        return factPojoHydrator.hydrate(
            scope,
            clazz
        )
    }

    override fun close() {
        queuers.forEach { (className, _) ->
            //clean up classname and shutdown a heartbeat thread.
            unwatch(className)
        }
        heartbeatExecutor.shutdown()
        channel?.shutdown() // shutdown netty managed channel.
    }
}

fun <T: P8eContract> ((Contract<T>) -> Boolean).toContractEventHandler(): ContractEventHandler<T> {
    return object: ContractEventHandler<T> {
        override fun handle(contract: Contract<T>): Boolean {
            return invoke(contract)
        }
    }
}

fun<T: P8eContract> ((ContractError<T>) -> Boolean).toContractErrorHandler(): ContractErrorHandler<T> {
    return object: ContractErrorHandler<T> {
        override fun handle(contractError: ContractError<T>): Boolean {
            return invoke(contractError)
        }
    }
}

fun ((ReconnectHandler) -> Unit).toDisconnectHandler(): DisconnectHandler {
    return object: DisconnectHandler {
        override fun handle(reconnectHandler: ReconnectHandler) {
            return invoke(reconnectHandler)
        }
    }
}
