package io.provenance.engine.contract.core

import io.p8e.classloader.MemoryClassLoader
import io.p8e.spec.P8eContract
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Ignore
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileInputStream
import java.security.Security
import java.util.UUID

@Ignore
class MemoryURLClassLoaderTest {

//    @Test
//    fun testExternalJarClassloading() {
//        Security.addProvider(BouncyCastleProvider())
//        val contractEngineSource = FileInputStream(
//            File(
//                System.getenv("CONTRACT_JAR_PATH") ?: throw IllegalStateException("Unable to find condition jar path.")
//            )
//        )
//
//        val classLoader = MemoryClassLoader("", contractEngineSource)
//
//        val clazz = classLoader.loadClass("io.p8e.contracts.OmniAccountContract")
//
//        assert(P8eContract::class.isInstance(clazz.constructors[0].newInstance()))
//    }
}