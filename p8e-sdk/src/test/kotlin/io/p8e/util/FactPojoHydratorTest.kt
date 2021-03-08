package io.p8e.util

// todo: update with open-source proto examples
//import com.nhaarman.mockitokotlin2.doReturn
//import com.nhaarman.mockitokotlin2.mock
//import io.p8e.annotations.Fact
//import io.p8e.client.P8eClient
//import io.p8e.proto.ContractScope.Record
//import io.p8e.proto.ContractScope.RecordGroup
//import io.p8e.proto.ContractScope.Scope
//import io.p8e.util.toProtoUuidProv
//import io.provenance.proto.BankProtos.FinancialAccount
//import io.provenance.proto.CustomerProtos.Name
//import io.provenance.proto.asset.AssetProtos.Asset
//import org.junit.jupiter.api.Test
//import org.junit.jupiter.api.assertThrows
//import java.lang.reflect.InvocationTargetException
//import java.util.UUID
//
//class FactPojoHydratorTest {
//
//    @Test
//    fun testFactHydrate() {
//        val certificateUuid = UUID(0, 1)
//
//        val assetOwnerUuid = UUID(0, 0).toProtoUuidProv()
//
//        val asset = Asset.newBuilder()
//            .setCashflowOwnerUuid(assetOwnerUuid)
//            .build()
//        val assetHash = asset.toByteArray().base64Sha512()
//
//        val accountNumber = "1234567"
//        val account = FinancialAccount.newBuilder()
//            .setAccountNumber(accountNumber)
//            .build()
//        val accountHash = account.toByteArray().base64Sha512()
//
//        val p8eClient = mock<P8eClient> {
//            on { loadProto(assetHash, asset.javaClass.name) } doReturn asset
//            on { loadProto(accountHash, account.javaClass.name) } doReturn account
//        }
//
//        val scope = Scope.newBuilder()
//            .addRecordGroup(
//                RecordGroup.newBuilder()
//                    .addRecords(
//                        Record.newBuilder()
//                            .setClassname(asset.javaClass.name)
//                            .setResultHash(assetHash)
//                            .setResultName("asset")
//                    ).addRecords(
//                        Record.newBuilder()
//                            .setClassname(account.javaClass.name)
//                            .setResultHash(accountHash)
//                            .setResultName("account")
//                    )
//            ).build()
//
//        val hydrator = FactPojoHydrator(
//            p8eClient
//        )
//
//        val thing = hydrator.hydrate(scope, Thing::class.java)
//        assert(thing.asset.cashflowOwnerUuid == assetOwnerUuid)
//        assert(thing.account.accountNumber == accountNumber)
//        assert(thing.scope != null)
//        assert(thing.name == null)
//
//        assertThrows<ContractDefinitionException> {
//            hydrator.hydrate(scope, NoAnnotation::class.java)
//        }
//
//        assertThrows<ContractDefinitionException> {
//            hydrator.hydrate(scope, NotMessage::class.java)
//        }
//
//        assertThrows<InvocationTargetException> {
//            hydrator.hydrate(scope, NotNullable::class.java)
//        }
//
//        assertThrows<ContractDefinitionException> {
//            hydrator.hydrate(scope, NoMatch::class.java)
//        }
//    }
//
//    private data class Thing(
//        @Fact("name") val name: Name?,
//        @Fact("scope") val scope: Scope,
//        @Fact("asset") val asset: Asset,
//        @Fact("account") val account: FinancialAccount
//    )
//
//    private data class NoAnnotation(
//        val name: Name,
//        @Fact("scope") val scope: Scope,
//        @Fact("asset") val asset: Asset,
//        @Fact("account") val account: FinancialAccount
//    )
//
//    private data class NotMessage(
//        @Fact("name") val name: String,
//        @Fact("scope") val scope: Scope,
//        @Fact("asset") val asset: Asset,
//        @Fact("account") val account: FinancialAccount
//    )
//
//    private data class NotNullable(
//        @Fact("name") val name: Name,
//        @Fact("scope") val scope: Scope,
//        @Fact("asset") val asset: Asset,
//        @Fact("account") val account: FinancialAccount
//    )
//
//    private data class NoMatch(
//        @Fact("name") val name: Name
//    )
//}
