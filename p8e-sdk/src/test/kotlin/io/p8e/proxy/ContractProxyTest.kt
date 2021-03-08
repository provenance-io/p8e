//package io.contractManager.proxy
//
//import io.contractManager.annotations.Consideration
//import io.contractManager.annotations.FactDef
//import io.contractManager.annotations.OutputDef
//import io.contractManager.annotations.ProposedDef
//import io.contractManager.definition.DefinitionService
//import io.contractManager.ContractManager
//import io.contractManager.P8EContractEngine
//import io.contractManager.spec.P8eContract
//import io.p8e.crypto.ProvenanceKeyGenerator
//import io.provenance.os.client.MockOsClient
//import io.provenance.proto.BankProtos.FinancialAccount
//import io.provenance.proto.contractManager.Common.BooleanResult
//import io.provenance.proto.contractManager.Common.Location
//import io.provenance.proto.contractManager.ContractSpecs.PartyType.AFFILIATE
//import io.provenance.proto.contractManager.ContractSpecs.PartyType.OMNIBUS
//import io.provenance.proto.contractManager.Contracts.*
//import io.provenance.proto.contractManager.Contracts.ExecutionResult.Result
//import org.bouncycastle.jce.provider.BouncyCastleProvider
//import org.junit.jupiter.api.Test
//import org.junit.jupiter.api.fail
//import java.security.Security
//import kotlin.reflect.jvm.jvmName
//
//class MockP8EEngine: P8eContractEngine {
//    override fun handle(contract: Contract): Contract {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//}
//
//class ContractProxyTest {
//    @Test
//    internal fun testProxyConsideration() {
//        Security.addProvider(BouncyCastleProvider())
//
//        val osClient = MockOsClient()
//        val keyPair = ProvenanceKeyGenerator.generateKeyPair()
//
//        val obj = osClient.put(
//            BooleanResult.newBuilder().setValue(false).build(),
//            keyPair.public,
//            { "some-signature".toByteArray() }
//        )
//
//        val definitionService = DefinitionService(
//            osClient,
//            keyPair
//        )
//        val considerationName = "consent"
//        val contract = Contract.newBuilder()
//            .addConsiderations(
//                ConsiderationProto.newBuilder()
//                    .setConsiderationName(considerationName)
//                    .setResult(
//                        ExecutionResult.newBuilder()
//                            .setResult(Result.PASS)
//                            .setFact(
//                                Fact.newBuilder()
//                                    .setName("fact-name")
//                                    .setDataLocation(
//                                        Location.newBuilder()
//                                            .setClassname(BooleanResult::class.jvmName)
//                                            .setUri(obj.obj.uri)
//                                    )
//                            )
//                    )
//            ).build()
//
//        val contractManager = ContractManager(definitionService, MockP8EEngine())
//        val omniAccountAgreement = ContractProxy.newProxy(
//            contractManager,
//            contract,
//            SomeContract::class.java,
//            ""
//        )
//
//        val consent = omniAccountAgreement.consent()
//        assert(!consent.value)
//
//        try {
//            omniAccountAgreement.accountCreated(
//                FinancialAccount.getDefaultInstance(),
//                consent
//            )
//            fail("Invocation of accountCreated should have failed since it doesn't have an execution result.")
//        } catch (expected: IllegalStateException) {}
//    }
//
//    @Test
//    internal fun testNoArgsAgreement() {
//        Security.addProvider(BouncyCastleProvider())
//
//        val osClient = MockOsClient()
//        val keyPair = ProvenanceKeyGenerator.generateKeyPair()
//
//        val obj = osClient.put(
//            BooleanResult.newBuilder().setValue(false).build(),
//            keyPair.public,
//            { "some-signature".toByteArray() }
//        )
//
//        val definitionService = DefinitionService(
//            osClient,
//            keyPair
//        )
//        val considerationName = "consent"
//        val contract = Contract.newBuilder()
//            .addConsiderations(
//                ConsiderationProto.newBuilder()
//                    .setConsiderationName(considerationName)
//                    .setResult(
//                        ExecutionResult.newBuilder()
//                            .setResult(Result.PASS)
//                            .setFact(
//                                Fact.newBuilder()
//                                    .setName("fact-name")
//                                    .setDataLocation(
//                                        Location.newBuilder()
//                                            .setClassname(BooleanResult::class.jvmName)
//                                            .setUri(obj.obj.uri)
//                                    )
//                            )
//                    )
//            ).build()
//
//        val contractManager = ContractManager(definitionService, MockP8EEngine())
//        val omniAccountAgreement = ContractProxy.newProxy(
//            contractManager,
//            contract,
//            NoArgsContract::class.java
//        )
//
//        val consent = omniAccountAgreement.consent()
//        assert(!consent.value)
//
//        try {
//            omniAccountAgreement.accountCreated(
//                FinancialAccount.getDefaultInstance(),
//                consent
//            )
//            fail("Invocation of accountCreated should have failed since it doesn't have an execution result.")
//        } catch (expected: IllegalStateException) {}
//    }
//}
//
//open class SomeContract(@FactDef("blah") val blah: String): P8eContract() {
//    /**
//     * In order for an affiliate to have an omnibus sub account created, they have to provide consent to do so.
//     */
//    @Consideration(AFFILIATE)
//    @OutputDef("omniAccountConsent")
//    open fun consent() = impliedConsideration()
//
//    /**
//     * Record the newly created account to the blockchain.
//     */
//    @Consideration(OMNIBUS)
//    @OutputDef("omniAccount")
//    open fun accountCreated(@ProposedDef("financialAccount") financialAccount: FinancialAccount,
//                       @FactDef("omniAccountConsent") omniAccountConsent: BooleanResult) = financialAccount
//}
//
//open class NoArgsContract: P8eContract() {
//    @Consideration(AFFILIATE)
//    @OutputDef("omniAccountConsent")
//    open fun consent() = impliedConsideration()
//
//    /**
//     * Record the newly created account to the blockchain.
//     */
//    @Consideration(OMNIBUS)
//    @OutputDef("omniAccount")
//    open fun accountCreated(@ProposedDef("financialAccount") financialAccount: FinancialAccount,
//                            @FactDef("omniAccountConsent") omniAccountConsent: BooleanResult) = financialAccount
//}
