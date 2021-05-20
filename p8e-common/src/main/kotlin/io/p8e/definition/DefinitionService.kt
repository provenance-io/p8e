package io.p8e.definition

import com.google.protobuf.Message
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.p8e.classloader.MemoryClassLoader
import io.p8e.crypto.SignerImpl
import io.p8e.proto.Common.DefinitionSpec
import io.p8e.proto.Common.Location
import io.p8e.proto.Contracts.Fact
import io.p8e.util.*
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.os.client.OsClient
import io.provenance.proto.encryption.EncryptionProtos.DIME
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.StringWriter
import java.lang.reflect.Method
import java.security.KeyPair
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class DefinitionService(
    private val osClient: OsClient,
    private val memoryClassLoader: MemoryClassLoader = MemoryClassLoader("", ByteArrayInputStream(ByteArray(0)))
) {

    companion object {
        private val byteCache = SizedConcurrentHashMap<ByteCacheKey, ByteArray>()
        private val putCache = SizedConcurrentHashMap<PutCacheKey, Boolean>()

        data class ByteCacheKey(
            val publicKey: PublicKey,
            val hash: String
        )

        data class PutCacheKey(
            val keys: Set<PublicKey>,
            val hash: String
        )
    }

    private val parseFromCache = ConcurrentHashMap<String, Method>()

    fun addJar(
        keyPair: KeyPair,
        definition: DefinitionSpec,
        signer: SignerImpl,
        signaturePublicKey: PublicKey? = null
    ) {
        return get(
            keyPair,
            definition.resourceLocation.ref.hash,
            definition.resourceLocation.classname,
            signer,
            signaturePublicKey
        ).let { memoryClassLoader.addJar(definition.resourceLocation.ref.hash, it) }
    }

    fun addJar(
        hash: String,
        inputStream: InputStream
    ) = memoryClassLoader.addJar(hash, inputStream)

    fun loadClass(
        keyPair: KeyPair,
        definition: DefinitionSpec,
        signer: SignerImpl,
        signaturePublicKey: PublicKey? = null
    ): Class<*> {
        return get(
            keyPair,
            definition.resourceLocation.ref.hash,
            definition.resourceLocation.classname,
            signer,
            signaturePublicKey
        ).let { memoryClassLoader.addJar(definition.resourceLocation.ref.hash, it) }
            .let {
                memoryClassLoader.loadClass(definition.resourceLocation.classname)
            }
    }

    fun loadClass(
        definition: DefinitionSpec
    ): Class<*> {
        return memoryClassLoader.loadClass(definition.resourceLocation.classname)
    }

    fun loadProto(
        keyPair: KeyPair,
        location: Location,
        signer: SignerImpl,
        signaturePublicKey: PublicKey? = null
    ): Message {
        return loadProto(
            keyPair,
            location.ref.hash,
            location.classname,
            signer,
            signaturePublicKey
        )
    }

    fun loadProto(
        keyPair: KeyPair,
        definition: DefinitionSpec,
        signer: SignerImpl,
        signaturePublicKey: PublicKey? = null
    ): Message {
        return loadProto(
            keyPair,
            definition.resourceLocation.ref.hash,
            definition.resourceLocation.classname,
            signer,
            signaturePublicKey
        )
    }

    fun loadProto(
        keyPair: KeyPair,
        fact: Fact,
        signer: SignerImpl,
        signaturePublicKey: PublicKey? = null
    ): Message {
        return loadProto(
            keyPair,
            fact.dataLocation.ref.hash,
            fact.dataLocation.classname,
            signer,
            signaturePublicKey
        )
    }

    fun loadProto(
        keyPair: KeyPair,
        hash: String,
        classname: String,
        signer: SignerImpl,
        signaturePublicKey: PublicKey? = null
    ): Message {
        return loadProto(
            get(
                keyPair,
                hash,
                classname,
                signer,
                signaturePublicKey
            ),
            classname
        )
    }

    private fun loadProto(
        inputStream: InputStream,
        classname: String
    ): Message {
        val clazz = Message::class.java
        val instanceOfThing = inputStream.let { inputStream ->
            parseFromCache.computeIfAbsent(classname) {
                Thread.currentThread().contextClassLoader.loadClass(classname)
                    .declaredMethods.find {
                    it.returnType.name == classname && it.name == "parseFrom" && it.parameterCount == 1 && it.parameters[0].type == InputStream::class.java
                }.orThrow { IllegalStateException("Unable to find parseFrom method on $classname") }
            }.invoke(
                null,
                inputStream
            )
        }

        if (!clazz.isAssignableFrom(instanceOfThing.javaClass)) {
            throw IllegalStateException("Unable to assign instance ${instanceOfThing::class.java.name} to type ${clazz.name}")
        }
        return clazz.cast(instanceOfThing)
    }

    fun get(
        encryptionKeyPair: KeyPair,
        hash: String,
        classname: String,
        signer: SignerImpl,
        signaturePublicKey: PublicKey? = null
    ): InputStream {

        //byteCache caches via signing public key
        return byteCache.computeIfAbsent(ByteCacheKey(encryptionKeyPair.public, hash)) {
            val item = try {
                osClient.get(hash.base64Decode(), encryptionKeyPair.public)
            } catch (e: StatusRuntimeException) {
                if (e.status.code == Status.Code.NOT_FOUND) {
                    throw NotFoundException(
                        """
                            Unable to find object
                            [classname: $classname]
                            [public key: ${encryptionKeyPair.public.toHex()}]
                            [hash: $hash]
                        """.trimIndent()
                    )
                }
                throw e
            }

            val bytes = item.use { dimeInputStream ->
                dimeInputStream.dime.audienceList
                    .map { ECUtils.convertBytesToPublicKey(it.publicKey.toString(Charsets.UTF_8).base64Decode()) }.toSet()
                    .let { putCache[PutCacheKey(it, hash)] = true }

                signaturePublicKey
                    ?.takeIf { publicKey ->
                        dimeInputStream.signatures
                            .map { it.publicKey.toString(Charsets.UTF_8) }
                            .contains(publicKeyToPem(publicKey))
                    }?.let { publicKey ->
                        dimeInputStream.getDecryptedPayload(encryptionKeyPair, publicKey, signer)
                    }.or {
                        dimeInputStream.getDecryptedPayload(encryptionKeyPair, signer)
                    }.use { signatureInputStream ->
                        signatureInputStream.readAllBytes()
                            .also {
                                if (!signatureInputStream.verify()) {
                                    throw NotFoundException(
                                        """
                                            Object was fetched but we're unable to verify item signature
                                            [classname: $classname]
                                            [encryption public key: ${encryptionKeyPair.public.toHex()}]
                                            [signing public key: ${signaturePublicKey?.toHex()}]
                                            [hash: $hash]
                                        """.trimIndent()
                                    )
                                }
                            }
                    }
            }

            // Drop the setting of additional audiences in cache to a thread to avoid recursive update
            thread {
                updateCache(
                    encryptionKeyPair.public,
                    item.dime,
                    hash,
                    bytes
                )
            }

            bytes
        }.let(::ByteArrayInputStream)
            .orThrow {
                NotFoundException(
                    """
                        Unable to find contract definition
                        [classname: $classname]
                        [public key: ${encryptionKeyPair.public.toHex()}]
                        [hash: $hash]
                    """.trimIndent()
                )
            }
    }

    fun save(
        keyPair: KeyPair,
        msg: ByteArray,
        signer: SignerImpl,
        audience: Set<PublicKey> = setOf()
    ): ByteArray {
        val putCacheKey = PutCacheKey(audience.toMutableSet().plus(keyPair.public), msg.base64Sha512())
        if (putCache[putCacheKey] == true) {
            return msg.sha512()
        }
        return osClient.put(
            ByteArrayInputStream(msg),
            keyPair.public,
            signer,
            msg.size.toLong(),
            audience
        ).also {
            putCache[PutCacheKey(audience.toMutableSet().plus(keyPair.public), msg.base64Sha512())] = true
        }.obj.unencryptedSha512
    }

    fun <T : Message> save(
        keyPair: KeyPair,
        msg: T,
        signer: SignerImpl,
        audience: Set<PublicKey> = setOf()
    ): ByteArray {
        val putCacheKey = PutCacheKey(audience.toMutableSet().plus(keyPair.public), msg.toByteArray().base64Sha512())
        if (putCache[putCacheKey] == true) {
            return msg.toByteArray().sha512()
        }
        return osClient.put(
            msg,
            keyPair.public,
            signer,
            additionalAudiences = audience
        ).also {
            putCache[putCacheKey] = true
        }.obj.unencryptedSha512
    }

    fun <T> forThread(fn: () -> T): T {
        return memoryClassLoader.forThread(fn)
    }

    private fun updateCache(
        publicKey: PublicKey,
        dime: DIME,
        hash: String,
        bytes: ByteArray
    ) {
        dime.audienceList
            .distinctBy { it.publicKey.toStringUtf8() }
            .map { ECUtils.convertBytesToPublicKey(it.publicKey.toStringUtf8().base64Decode()) }
            .filter { it != publicKey }
            .forEach {
                byteCache.computeIfAbsent(ByteCacheKey(it, hash)) {
                    bytes
                }
            }
    }

    private fun publicKeyToPem(publicKey: PublicKey): String {
        val pemStrWriter = StringWriter()
        val pemWriter = JcaPEMWriter(pemStrWriter)
        pemWriter.writeObject(publicKey)
        pemWriter.close()
        return pemStrWriter.toString()
    }
}
