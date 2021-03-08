package io.p8e.classloader

// todo: update with open-source contract
//import com.google.protobuf.util.JsonFormat
//import io.p8e.contracts.OnboardAssetContract
//import io.p8e.contracts.TokenContract
//import io.p8e.proto.Common.BooleanResult
//import io.p8e.proto.Common.ProvenanceReference
//import io.p8e.proto.ContractScope.Scope
//import io.p8e.spec.ContractSpecMapper
//import io.p8e.spec.P8eContract
//import io.p8e.proto.Util.UUID
//import org.bouncycastle.util.encoders.Hex
//import org.junit.jupiter.api.Test
//import java.io.FileInputStream
//
//class MemoryClassLoaderTest {
//
//    @Test
//    fun testParentFirstClassLoader() {
//        val classLoader = TokenContract::class
//            .java
//            .protectionDomain
//            .codeSource
//            .location
//            .path
//            .let { FileInputStream(it) }
//            .use {
//                MemoryClassLoader(
//                    "some-hash",
//                    it
//                )
//            }
//
//        val clazz = classLoader.loadClass(TokenContract::class.java.name)
//
//        assert(P8eContract::class.java.isAssignableFrom(clazz))
//        assert(TokenContract::class.java.name == clazz.name)
//        assert(TokenContract::class.java != clazz)
//
//        val booleanResultClass = clazz.declaredMethods.find { it.name == "requestToken" }?.returnType
//        assert(booleanResultClass != null)
//        assert(booleanResultClass?.name == BooleanResult::class.java.name)
//        assert(booleanResultClass == BooleanResult::class.java)
//
//        val uuidClass = clazz.superclass.declaredFields.find { it.name == "uuid" }?.type
//        assert(uuidClass != null)
//        assert(uuidClass?.name == UUID::class.java.name)
//        assert(uuidClass == UUID::class.java)
//    }
//}
