package io.provenance.engine.contract.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class ContractEngineTest {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

//    private val osClient = OsClient(ObjectMapper().configureProvenance(), OsClientProperties("http://localhost:8080/object-store/internal"))
//
//    private val privateKey = "00B1B26B21436DFA3D64C69E6E36343B0763C4F0242E8A6213CDE15CCF00D3F1B4"
//        .hexStringToByteArray()
//        .let(ECUtils::convertBytesToPrivateKey)
//
//    private val publicKey = "04844C11C4D51E0CAECF2FB72ED56AE2021018BA442987AC5A6D95C215563D3399FD0E43197667D06F07B01C761FFD87780A836A24EB9C1EBFCAFBEE35A6AA67B5"
//        .hexStringToByteArray()
//        .let(ECUtils::convertBytesToPublicKey)
//
//    private val pen = Pen(ECUtils.privateKeyToPem(privateKey), ECUtils.publicKeyToPem(publicKey))
//
//    @Test
//    internal fun testFactLoading() {
//        val factOnboardingSpec = ContractSpec.newBuilder()
//            .addRecitalSpecs(SignerRole.OWNER.toRecitalSpec())
//            .addRecitalSpecs(SignerRole.CUSTODIAN.toRecitalSpec())
//            .addConditionSpecs(
//                ConditionSpec.newBuilder()
//                    .setConditionName("FactLoader")
//                    .addPartyRoles(SignerRole.OWNER)
//                    .addInputSpecs(
//                        DefinitionSpec.newBuilder()
//                            .setName("Fact")
//                            .setUri("object://unsigned-sha-512-of-contract-proto-jar")
//                            .setVersion(SignedSHA512.getDefaultInstance()) // TODO fix this
//                            .setProtoClassname(Fact::class.jvmName)
//                    )
//            ).build()
//
//        val signedSha512 = pen.sign(factOnboardingSpec)
//        val specItem = osClient.put(factOnboardingSpec, publicKey)
//
//        val factOnboardingContract = Contract.newBuilder()
//            .setSpec(
//                ContractSpecRef.newBuilder()
//                    .setUri(specItem.obj.uri)
//                    .setVersion(signedSha512)
//            )
//    }
}
