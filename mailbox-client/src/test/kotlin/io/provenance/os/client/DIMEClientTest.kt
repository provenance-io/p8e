package io.provenance.os.client

//import com.fasterxml.jackson.databind.ObjectMapper
//import io.provenance.p8e.encryption.ecies.ECUtils
//import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
//import io.p8e.util.configureProvenance
//import io.provenance.os.util.CertificateUtil
//import io.provenance.os.util.CertificateUtil
//import io.provenance.os.util.base64Decode
//import io.provenance.proto.BankProtos.FinancialAccount
//import io.provenance.proto.ParticipantProtos
//import org.apache.commons.io.IOUtils
//import org.bouncycastle.jce.provider.BouncyCastleProvider
//import org.bouncycastle.util.encoders.Hex
//import org.junit.jupiter.api.Test
//import java.io.File
//import java.io.FileInputStream
//import java.io.FileOutputStream
//import java.security.KeyPair
//import java.security.PrivateKey
//import java.security.Security
//import java.util.Arrays
//import java.util.UUID

// class DIMEClientTest {
//
//     private val publicKey = CertificateHelper.pemToPublicKey("-----BEGIN PUBLIC KEY-----\n" + "MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEqQjYVX+zhskubZ2SG/SvNWyDK7Ci/kGs\n" + "I+Ex6Nmjlqdk+1U8WgcJlb3adTPPayQ/PNPLkSUo8MDcapujZRfk2g==\n" + "-----END PUBLIC KEY-----")
//
//     private val privateKey: PrivateKey = CertificateHelper.privateKeyFromPem("-----BEGIN EC PRIVATE KEY-----\n" + "MHQCAQEEIIBOmitdAdwZo0uQkdLtz2pZH1kQ9IXCkCBWapigyuvWoAcGBSuBBAAK\n" + "oUQDQgAEqQjYVX+zhskubZ2SG/SvNWyDK7Ci/kGsI+Ex6Nmjlqdk+1U8WgcJlb3a\n" + "dTPPayQ/PNPLkSUo8MDcapujZRfk2g==\n" + "-----END EC PRIVATE KEY-----")
//
//     private val signaturePrivateKey = "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg4/W4ZYjiRv6eMJ4K\ngToiAaxWVvZVPSmJybqCbhPcMOihRANCAASV7pfbLFQAbDpf5Jfoh5OH67O1BLms\novgeT9oIigulNaB6SD6Yya+BEKNaP2xKcBveU3bpDnWpjX1UV8j0FqBi\n-----END PRIVATE KEY-----"
//         .let(CertificateUtil::ecdsaPemToPrivateKey)
//
//     private val signaturePublicKey = "-----BEGIN CERTIFICATE-----\nMIICpzCCAkygAwIBAgIUO9tgRjLUQoqOFmQEVweLR9cSLJ0wCgYIKoZIzj0EAwIw\ngYkxCzAJBgNVBAYTAlVTMQswCQYDVQQIDAJDQTEWMBQGA1UEBwwNU2FuIEZyYW5j\naXNjbzETMBEGA1UECgwKUHJvdmVuYW5jZTETMBEGA1UECwwKRm91bmRhdGlvbjET\nMBEGA1UECwwKUHJvdmVuYW5jZTEWMBQGA1UEAwwNUHJvdmVuYW5jZSBDQTAeFw0x\nOTA3MDkxNDIzNDRaFw0xOTA4MTAxNDI0MTRaMIGeMQwwCgYDVQQGEwNVU0ExCzAJ\nBgNVBAgTAkNBMRYwFAYDVQQHEw1TYW4gRnJhbmNpc2NvMRcwFQYDVQQJEw42NTAg\nQ2FsaWZvcm5pYTEOMAwGA1UEERMFOTQxMDgxEzARBgNVBAoTClByb3ZlbmFuY2Ux\nEzARBgNVBAsTCkFmZmlsaWF0ZXMxFjAUBgNVBAMTDVRyYXZpcyBBbHBlcnMwWTAT\nBgcqhkjOPQIBBggqhkjOPQMBBwNCAASV7pfbLFQAbDpf5Jfoh5OH67O1BLmsovge\nT9oIigulNaB6SD6Yya+BEKNaP2xKcBveU3bpDnWpjX1UV8j0FqBio3sweTAOBgNV\nHQ8BAf8EBAMCA6gwJwYDVR0lBCAwHgYIKwYBBQUHAwEGCCsGAQUFBwMCBggqAwQF\nBgcIATAdBgNVHQ4EFgQU50ERk4Vd86ve1+/OMmJI5Q1r4LMwHwYDVR0jBBgwFoAU\nWlAk7VlJ3IGW9eWA435kPVFYm7IwCgYIKoZIzj0EAwIDSQAwRgIhANkf1JxsVeVk\nb1iFjj0KqS4AssessHv1r9mTd8xwH5mZAiEAzRjixVMw78DbFbZ/6I0o18ybs4fa\ndlwfoxpUmSqLICs=\n-----END CERTIFICATE-----"
//         .let(CertificateUtil::x509PemToPublicKey)
//
//     private val signingKeyPair = KeyPair(signaturePublicKey, signaturePrivateKey)
//
// //    @Test
// //    fun testDIMEFileFormatSerdeWithDecryption() {
// //        val signature = "some-fucking-signature"
// //        Security.addProvider(BouncyCastleProvider())
// //        val memberKeyPair = ProvenanceKeyGenerator.generateKeyPair()
// //
// //        val osClient = OsClient(ObjectMapper().configureProvenance(), OsClientProperties("http://localhost:8080/${OsClient.CONTEXT}/internal"))
// //
// //        val file1Name = "/Users/talpers/Downloads/RTd80446e6ed181be6cda4eaf41f484972.mkv"
// //        val file2Name = "/Users/talpers/Downloads/RTd80446e6ed181be6cda4eaf41f484972-2.mkv"
// //        val file3Name = "/Users/talpers/Downloads/RTd80446e6ed181be6cda4eaf41f484972-3.mkv"
// //
// ////        for (i in 0..10) {
// ////            try {
// ////                osClient.get(UUID.randomUUID())
// ////            } catch (ignored: Throwable) {}
// ////        }
// //
// //        var start = System.currentTimeMillis()
// //        val file = File(file1Name)
// //        val result = FileInputStream(file)
// //            .use { fis ->
// //                osClient.put(
// //                    fis,
// //                    memberKeyPair.public,
// //                    file.length(),
// //                    { signature.toByteArray() }
// //                )
// //            }
// //        logger().info("Timing for first put: ${System.currentTimeMillis() - start} ms.")
// //
// //        assert(result.obj.signatures.first().toString(Charsets.UTF_8) == signature)
// //
// //        start = System.currentTimeMillis()
// //        val dimeInputStream = osClient.get(result.obj.unencryptedSha512, memberKeyPair.public)
// //        logger().info("Timing for first get: ${System.currentTimeMillis() - start}")
// //
// //        assert(dimeInputStream.signatures.size == result.obj.signatures.size)
// //        assert(dimeInputStream.signatures.first().toString(Charsets.UTF_8) == result.obj.signatures.first().toString(Charsets.UTF_8))
// //
// //        dimeInputStream.getDecryptedPayload(memberKeyPair)
// //            .use { decryptedInputStream ->
// //                FileOutputStream(File(file2Name))
// //                    .use { fos ->
// //                        IOUtils.copy(decryptedInputStream, fos)
// //                    }
// //            }
// //
// //        assert(dimeInputStream.uuid == result.obj.objectUuid)
// //        val file2 = File(file2Name)
// //        start = System.currentTimeMillis()
// //        val updateResult = FileInputStream(file2)
// //            .use { fis ->
// //                osClient.put(
// //                    fis,
// //                    memberKeyPair.public,
// //                    file.length(),
// //                    { signature.toByteArray()},
// //                    uuid = result.obj.objectUuid
// //                )
// //            }
// //        logger().info("Timing for second put: ${System.currentTimeMillis() - start}")
// //
// //        assert(updateResult.obj.objectUuid == result.obj.objectUuid)
// //
// //        start = System.currentTimeMillis()
// //        val updatedDimeInputStream = osClient.get(
// //            updateResult.obj.uri,
// //            memberKeyPair.public
// //        )
// //        logger().info("Timing for second get: ${System.currentTimeMillis() - start}")
// //
// //        assert(updatedDimeInputStream.uuid == updateResult.obj.objectUuid)
// //
// //        updatedDimeInputStream.getDecryptedPayload(memberKeyPair)
// //            .use { decryptedInputStream ->
// //                FileOutputStream(File(file3Name))
// //                    .use { fos ->
// //                        IOUtils.copy(decryptedInputStream, fos)
// //                    }
// //            }
// //    }
//
// //    @Test
// //    internal fun testUploadSpeed() {
// //        val signature = "some-fucking-signature"
// //        Security.addProvider(BouncyCastleProvider())
// //        val memberKeyPair = ProvenanceKeyGenerator.generateKeyPair()
// //
// //        val osClient = OsClient(ObjectMapper().configureProvenance(), OsClientProperties("http://localhost:8080/${OsClient.CONTEXT}/internal"))
// //
// //        val file1Name = "/Users/talpers/Downloads/star_trek.png"
// //
// ////        for (i in 0..10) {
// ////            try {
// ////                osClient.get(UUID.randomUUID())
// ////            } catch (ignored: Throwable) {}
// ////        }
// //
// ////        for (i in 0..100) {
// //            val start = System.currentTimeMillis()
// //            val file = File(file1Name)
// //            val result = FileInputStream(file)
// //                .use { fis ->
// //                    osClient.put(
// //                        fis,
// //                        memberKeyPair.public,
// //                        file.length(),
// //                        { signature.toByteArray() }
// //                    )
// //                }
// //            val elapsed = System.currentTimeMillis() - start
// //            logger().info("Upload took $elapsed ms")
// //            logger().info("Uploaded at ${(result.item.contentLength / 1024 / 1024).toDouble() / (elapsed.toDouble() / 1000.0)}MB/s")
// //
// //            assert(result.obj.signatures.first().toString(Charsets.UTF_8) == signature)
// //
// //        val item = osClient.get(
// //            result.obj.unencryptedSha512,
// //            memberKeyPair.public
// //        ).getDecryptedPayload(memberKeyPair).readAllBytes()
// //
// //        val file2 = File("/Users/talpers/Downloads/star_trek-whatthefuck.png")
// //        FileOutputStream(file2)
// //            .use {
// //                it.write(item)
// //            }
// ////        }
// //    }
// //
//     @Test
//     fun test() {
//         Security.addProvider(BouncyCastleProvider())
//
//         val privateKey = CertificateHelper.privateKeyFromPem("-----BEGIN EC PRIVATE KEY-----\n" + "MHQCAQEEICO2Pinj+t1bJHKJxzF4rW2l3Pn2u8wlQWc8zD6warCZoAcGBSuBBAAK\n" + "oUQDQgAEww1wZU/EPs/IUeuk6KGx5oOarv9BQAAOkfik2JF7n1kMCmtbQFxjK2gS\n" + "QxBgvGw0cGdwpLyCw5qISSY96XghKw==\n" + "-----END EC PRIVATE KEY-----")
//         val publicKey = ECUtils.convertBytesToPublicKey("BMMNcGVPxD7PyFHrpOihseaDmq7/QUAADpH4pNiRe59ZDAprW0BcYytoEkMQYLxsNHBncKS8gsOaiEkmPel4ISs=".base64Decode())
//
//         val publicKey2 = ECUtils.convertBytesToPublicKey(Hex.decode("045fcab3681217e723ef0eb05546ece331f6f46b02e634614976f0489ed13af57eea126a80c860f3a3aa9df95c65a9ae9e0bdcf3f4edf78e191afc88de6c66536e"))
//
//         val keyPair = KeyPair(publicKey, privateKey)
//         val osClient = OsClient(ObjectMapper().configureProvenance(), OsClientProperties("http://localhost:8080/${OsClient.CONTEXT}/internal"))
//
//         val file = File("/Users/talpers/logs.txt")
//         val obj = FileInputStream(file)
//             .use {
//                 osClient.put(
//                     it,
//                     publicKey,
//                     signingKeyPair,
//                     file.length(),
//                     additionalAudiences = setOf(publicKey2)
//                 )
//             }
//         osClient.get(obj.obj.unencryptedSha512, keyPair.public)
//             .getDecryptedPayload(keyPair)
//             .use {
//                 assert(Arrays.equals(FileInputStream(file).readAllBytes(), it.readAllBytes()))
//                 assert(it.verify())
//             }
//
//     }
// //
// //    @Test
// //    fun test1() {
// //        val publicKey = CertificateHelper.pemToPublicKey("-----BEGIN PUBLIC KEY-----\n" + "MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEqQjYVX+zhskubZ2SG/SvNWyDK7Ci/kGs\n" + "I+Ex6Nmjlqdk+1U8WgcJlb3adTPPayQ/PNPLkSUo8MDcapujZRfk2g==\n" + "-----END PUBLIC KEY-----")
// //        val privateKey: PrivateKey = CertificateHelper.privateKeyFromPem("-----BEGIN EC PRIVATE KEY-----\n" + "MHQCAQEEIIBOmitdAdwZo0uQkdLtz2pZH1kQ9IXCkCBWapigyuvWoAcGBSuBBAAK\n" + "oUQDQgAEqQjYVX+zhskubZ2SG/SvNWyDK7Ci/kGsI+Ex6Nmjlqdk+1U8WgcJlb3a\n" + "dTPPayQ/PNPLkSUo8MDcapujZRfk2g==\n" + "-----END EC PRIVATE KEY-----")
// //
// //        val keyPair = KeyPair(publicKey, privateKey)
// //        val osClient = OsClient(ObjectMapper().configureProvenance(), OsClientProperties("http://localhost:8081/${OsClient.CONTEXT}/internal"))
// //
// //        osClient.createPublicKey(
// //            publicKey
// //        )
// //    }
// }
