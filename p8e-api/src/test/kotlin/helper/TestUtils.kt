package helper

import io.p8e.grpc.Constant
import io.p8e.proto.Authentication
import io.p8e.proto.Common
import io.p8e.proto.ContractScope
import io.p8e.proto.ContractSpecs
import io.p8e.proto.Contracts
import io.p8e.proto.PK
import io.p8e.util.toByteString
import io.p8e.util.toProtoTimestampProv
import io.p8e.util.toProtoUuidProv
import io.p8e.util.toPublicKeyProto
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.p8e.shared.domain.ScopeRecord
import org.jetbrains.exposed.sql.Database
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.time.OffsetDateTime
import java.util.*

class TestUtils {
    companion object {

        private val scopeUuid = UUID.randomUUID().toProtoUuidProv()
        private val groupUuid = UUID.randomUUID().toProtoUuidProv()

        fun DatabaseConnect() {
            Database.connect(
                url = listOf(
                    "jdbc:h2:mem:test",
                    "DB_CLOSE_DELAY=-1",
                    "LOCK_TIMEOUT=10000",
                    "INIT=" + listOf(
                        "create domain if not exists jsonb as other",
                        "create domain if not exists TIMESTAMPTZ as TIMESTAMP"
                    ).joinToString("\\;")
                ).joinToString(";") + ";",
                driver = "org.h2.Driver"
            )
        }

        fun generateKeyPair() = ProvenanceKeyGenerator.generateKeyPair()

        fun generateAuthenticationToken(expirationSec: Long): Authentication.AuthenticationToken =
            Authentication.AuthenticationToken.newBuilder()
                .setRandomData(UUID.randomUUID().toString().toByteString())
                .setExpiration(OffsetDateTime.now().plusSeconds(expirationSec).toProtoTimestampProv())
                .build()

        fun generateJavaSecuritySignature(privateKey: PrivateKey, token: Authentication.AuthenticationToken) =
            Signature.getInstance(Constant.JWT_ALGORITHM).apply {
                initSign(privateKey)
                update(token.toByteArray())
            }.let {
                it.sign()
            }

        fun generateTestContract(keys: KeyPair, scopeData: ScopeRecord): Contracts.Contract =
            Contracts.Contract.newBuilder()
                .setDefinition(
                    Common.DefinitionSpec.newBuilder()
                        .setName("def-contract-name")
                        .setResourceLocation(
                            Common.Location.newBuilder()
                                .setRef(
                                    Common.ProvenanceReference.newBuilder()
                                        .setScopeUuid(scopeData.scopeUuid.toProtoUuidProv())
                                        .setGroupUuid(groupUuid)
                                        .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==")
                                        .setName("some-name")
                                        .build()
                                )
                                .setClassname("HelloWorldContract")
                        )
                        .setSignature(
                            Common.Signature.newBuilder()
                                .setAlgo("algo")
                                .setProvider("provider")
                                .setSignature(UUID.randomUUID().toString())
                                .setSigner(
                                    PK.SigningAndEncryptionPublicKeys.newBuilder()
                                        .setEncryptionPublicKey(keys.public.toPublicKeyProto())
                                        .setSigningPublicKey(keys.public.toPublicKeyProto())
                                        .build()
                                )
                                .build()
                        )
                )
                .setType(Contracts.ContractType.FACT_BASED)
                .setSpec(
                    Contracts.Fact.newBuilder()
                        .setDataLocation(
                            Common.Location.newBuilder()
                                .setRef(
                                    Common.ProvenanceReference.newBuilder()
                                        .setScopeUuid(scopeData.scopeUuid.toProtoUuidProv())
                                        .setGroupUuid(groupUuid)
                                        .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==")
                                        .setName("some-name")
                                        .build()
                                )
                                .setClassname("HelloWorldContract")
                                .build()
                        )
                        .setName("name")
                        .build()
                )
                .setInvoker(
                    PK.SigningAndEncryptionPublicKeys.newBuilder()
                        .setEncryptionPublicKey(keys.public.toPublicKeyProto())
                        .setSigningPublicKey(keys.public.toPublicKeyProto())
                        .build()
                )
                .addAllInputs(
                    mutableListOf<Contracts.Fact>(
                        Contracts.Fact.newBuilder()
                            .setDataLocation(
                                Common.Location.newBuilder()
                                    .setRef(
                                        Common.ProvenanceReference.newBuilder()
                                            .setScopeUuid(scopeData.scopeUuid.toProtoUuidProv())
                                            .setGroupUuid(groupUuid)
                                            .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==")
                                            .setName("some-name")
                                            .build()
                                    )
                                    .setClassname("HelloWorldContract")
                                    .build()
                            )
                            .setName("name")
                            .build()
                    )
                )
                .addAllConditions(
                    mutableListOf(
                        Contracts.ConditionProto.newBuilder()
                            .setConditionName("some-condition-name")
                            .setResult(
                                Contracts.ExecutionResult.newBuilder()
                                    .setOutput(
                                        Contracts.ProposedFact.newBuilder()
                                            .setName("name")
                                            .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==")
                                            .setClassname("HelloWorldContract")
                                            .setAncestor(Common.ProvenanceReference.getDefaultInstance())
                                            .build()
                                    )
                                    .setResult(Contracts.ExecutionResult.Result.PASS)
                                    .setRecordedAt(OffsetDateTime.now().toProtoTimestampProv())
                                    .build()
                            )
                            .build()
                    )
                )
                .addAllConsiderations(
                    mutableListOf(
                        Contracts.ConsiderationProto.newBuilder()
                            .setConsiderationName("name")
                            .addAllInputs(
                                mutableListOf(
                                    Contracts.ProposedFact.newBuilder()
                                        .setName("name")
                                        .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==h")
                                        .setClassname("HelloWorldContract")
                                        .setAncestor(Common.ProvenanceReference.getDefaultInstance())
                                        .build()
                                )
                            )
                            .setResult(
                                Contracts.ExecutionResult.newBuilder()
                                    .setOutput(
                                        Contracts.ProposedFact.newBuilder()
                                            .setName("name")
                                            .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==")
                                            .setClassname("HelloWorldContract")
                                            .setAncestor(Common.ProvenanceReference.getDefaultInstance())
                                            .build()
                                    )
                                    .setResult(Contracts.ExecutionResult.Result.PASS)
                                    .setRecordedAt(OffsetDateTime.now().toProtoTimestampProv())
                                    .build()
                            )
                            .build()
                    )
                )
                .addAllRecitals(
                    mutableListOf(
                        Contracts.Recital.newBuilder()
                            .setSignerRole(ContractSpecs.PartyType.OWNER)
                            .setSigner(
                                PK.SigningAndEncryptionPublicKeys.newBuilder()
                                    .setEncryptionPublicKey(keys.public.toPublicKeyProto())
                                    .setSigningPublicKey(keys.public.toPublicKeyProto())
                                    .build()
                            )
                            .setAddress("some-address".toByteString())
                            .build()
                    )
                )
                .build()

        fun generateTestEnvelope(keys: KeyPair, scopeData: ScopeRecord, scopeWithLastEvent: Boolean = true, executionUUID: UUID? = null, contract: Contracts.Contract? = null): ContractScope.Envelope {
            val executionUuid = executionUUID?.toProtoUuidProv() ?: UUID.randomUUID().toProtoUuidProv()
            val contract = contract ?: generateTestContract(keys, scopeData)

            val lastEvent = if(scopeWithLastEvent) {
                ContractScope.Event.newBuilder()
                    .setExecutionUuid(executionUuid)
                    .setGroupUuid(groupUuid)
                    .build()
            } else {
                ContractScope.Event.getDefaultInstance()
            }

            return ContractScope.Envelope.newBuilder()
                .setRef(
                    Common.ProvenanceReference.newBuilder()
                        .setScopeUuid(scopeData.scopeUuid.toProtoUuidProv())
                        .setGroupUuid(groupUuid)
                        .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==")
                        .setName("some-name")
                        .build() // Build ProvenanceReference
                )
                .setContract(contract)
                .addAllSignatures(
                    mutableListOf(
                        Common.Signature.newBuilder()
                            .setAlgo("algo")
                            .setProvider("provider")
                            .setSignature(UUID.randomUUID().toString())
                            .setSigner(
                                PK.SigningAndEncryptionPublicKeys.newBuilder()
                                    .setEncryptionPublicKey(keys.public.toPublicKeyProto())
                                    .setSigningPublicKey(keys.public.toPublicKeyProto())
                                    .build() // Build Signer
                            )
                            .build() // Build Signature
                    )
                )
                .setExecutionUuid(executionUuid)
                .setScope(
                    ContractScope.Scope.newBuilder()
                        .setUuid(UUID.randomUUID().toProtoUuidProv())
                        .addAllParties(
                            mutableListOf(
                                Contracts.Recital.newBuilder()
                                    .setSignerRole(ContractSpecs.PartyType.OWNER)
                                    .setSigner(
                                        PK.SigningAndEncryptionPublicKeys.newBuilder()
                                            .setEncryptionPublicKey(keys.public.toPublicKeyProto())
                                            .setSigningPublicKey(keys.public.toPublicKeyProto())
                                            .build() // Build Signer
                                    )
                                    .setAddress("some-address".toByteString())
                                    .build() // Build Recital
                            )
                        )
                        .addAllRecordGroup(
                            mutableListOf(
                                ContractScope.RecordGroup.newBuilder()
                                    .setSpecification("some-hash-specification")
                                    .setGroupUuid(groupUuid)
                                    .setExecutor(
                                        PK.SigningAndEncryptionPublicKeys.newBuilder()
                                            .setEncryptionPublicKey(keys.public.toPublicKeyProto())
                                            .setSigningPublicKey(keys.public.toPublicKeyProto())
                                            .build() // Build Executor
                                    )
                                    .addAllParties(
                                        mutableListOf(
                                            Contracts.Recital.newBuilder()
                                                .setSignerRole(ContractSpecs.PartyType.OWNER)
                                                .setSigner(
                                                    PK.SigningAndEncryptionPublicKeys.newBuilder()
                                                        .setEncryptionPublicKey(keys.public.toPublicKeyProto())
                                                        .setSigningPublicKey(keys.public.toPublicKeyProto())
                                                        .build() // Build Signer
                                                )
                                                .setAddress("some-address".toByteString())
                                                .build() // Build Recital
                                        )
                                    )
                                    .addAllRecords(
                                        mutableListOf(
                                            ContractScope.Record.newBuilder()
                                                .setName("some-name")
                                                .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==")
                                                .setClassname("HelloWorldContract")
                                                .addAllInputs(
                                                    mutableListOf(
                                                        ContractScope.RecordInput.newBuilder()
                                                            .setName("some-name")
                                                            .setHash("AyZYcO1gmfndNHU+v4ltISy+nZb5rdwNjfc5+Q66dbpC3tlfF5Nt79usqSZjIz8h3HoJEYcIz3LE7sM09uTUXg==")
                                                            .setClassname("HelloWorldContract")
                                                            .setType(ContractScope.RecordInput.Type.PROPOSED)
                                                            .build() // Build RecordInput
                                                    )
                                                )
                                                .setResult(Contracts.ExecutionResult.Result.PASS)
                                                .setResultName("some-result-name")
                                                .setResultHash("some-result-hash")
                                                .build() // Build Record
                                        )

                                    )
                                    .setClassname("HelloWorldContract")
                                    .build() // Build RecordGroup
                            )
                        )
                        .setLastEvent(lastEvent)
                        .build() // Build Scope
                )
                .setStatus(ContractScope.Envelope.Status.CREATED)
                .build() // Build Envelope
        }
    }
}