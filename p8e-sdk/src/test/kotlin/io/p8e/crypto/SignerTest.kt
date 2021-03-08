//package io.p8e.crypto
//
//import com.github.javafaker.Faker
//import io.p8e.util.base64Encode
//import io.p8e.util.randomProtoUuidProv
//import io.provenance.proto.affiliate.AffiliateProtos.AffiliateCertificate
//import org.junit.jupiter.api.Assertions
//import org.junit.jupiter.api.Test
//
//class SignerTest {
//    private val affiliateUuid = randomProtoUuidProv()
//    private val certificateUuid = randomProtoUuidProv()
//    private val certificate = AffiliateCertificate.newBuilder()
//        .setUuid(certificateUuid)
//        .build()
//
//    @Test
//    fun testSign() {
//        val pen = Pen(
//            Pen.testPrivKey,
//            Pen.testPubKey,
//            certificate
//        )
//
//        val signature = pen.sign(Faker.instance().chuckNorris().fact())
//        Assertions.assertNotNull(signature.signature)
//        Assertions.assertNotNull(signature.algo)
//        Assertions.assertNotNull(signature.provider)
//        Assertions.assertNotNull(signature.signer)
//        Assertions.assertNotNull(signature.signer.certificate)
//        Assertions.assertNotNull(signature.signer.dn)
//    }
//
//    @Test
//    fun testVerify() {
//        val pen = Pen(
//            Pen.testPrivKey,
//            Pen.testPubKey,
//            certificate
//        )
//
//        val fact = Faker.instance().chuckNorris().fact()
//        val signature = pen.sign(fact)
//
//        val lab = Lens(Pen.testPubKey)
//        //Assertions.assertTrue(lab.verify(fact.base64Encode(), signature))
//    }
//}
