package io.p8e.proxy

import com.nhaarman.mockitokotlin2.eq
import io.p8e.ContractManager
import io.p8e.client.P8eClient
import io.p8e.index.client.IndexClient
import io.p8e.proto.Common
import io.p8e.proto.Contracts
import io.p8e.util.base64Sha512
import io.p8e.util.base64String
import io.p8e.util.loBytes
import io.p8e.util.sha256
import io.p8e.util.sha512
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.argThat
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files.readAllBytes
import java.security.PublicKey
import javax.management.Query.eq
import kotlin.random.Random

fun <T> nonNullArgThat(ret: T, matcher: ArgumentMatcher<T>): T {
    ArgumentMatchers.argThat<T>(matcher)
    return ret
}

class PermissionUpdaterTest {
    lateinit var contractManager: ContractManager
    val keyPairs = listOf(
        ProvenanceKeyGenerator.generateKeyPair(),
        ProvenanceKeyGenerator.generateKeyPair()
    )
    val audience: Set<PublicKey> = keyPairs.map { it.public }.toSet()

    @BeforeEach
    fun setup() {
        val client = mock(P8eClient::class.java)
        val indexClient = mock(IndexClient::class.java)
        contractManager = ContractManager(
            client,
            indexClient,
            keyPairs[0],
        )
    }

    fun getPermissionUpdater(records: List<Contracts.Fact>): PermissionUpdater {
        return PermissionUpdater(
            contractManager,
            Contracts.Contract.newBuilder()
                .addAllInputs(records)
                .build(),
            audience,
        )
    }

    @Test
    fun `PermissionUpdater properly handles sha512 hash saving`() {
        val bytes = Random.nextBytes(1000)
        val sha512Hash = bytes.base64Sha512()

        val records = listOf(Contracts.Fact.newBuilder()
            .apply {
                dataLocationBuilder.refBuilder.hash = sha512Hash
            }
            .build())
        `when`(contractManager.client.loadObject(sha512Hash)).thenReturn(bytes)

        val permissionUpdater = getPermissionUpdater(records)

        `when`(contractManager.client.storeObject(
            nonNullArgThat(ByteArrayInputStream(bytes)) { stream -> stream.readAllBytes().contentEquals(bytes).also { stream.reset() } },
            eq(audience), sha256 = eq(false), loHash = eq(false))
        ).thenReturn(
            Common.Location.getDefaultInstance()
        )

        permissionUpdater.saveConstructorArguments()

        verify(contractManager.client, times(1)).storeObject(
            nonNullArgThat(ByteArrayInputStream(bytes)) { stream -> stream.readAllBytes().contentEquals(bytes).also { stream.reset() } },
            eq(audience),
            sha256 = eq(false), // properly detected NOT sha256 (sha512)
            loHash = eq(false) // properly detected full hash
        )
    }

    @Test
    fun `PermissionUpdater properly handles sha256 hash saving`() {
        val bytes = Random.nextBytes(1000)
        val sha256Hash = bytes.sha256().base64String()

        val records = listOf(Contracts.Fact.newBuilder()
            .apply {
                dataLocationBuilder.refBuilder.hash = sha256Hash
            }
            .build())
        `when`(contractManager.client.loadObject(sha256Hash)).thenReturn(bytes)

        val permissionUpdater = getPermissionUpdater(records)

        `when`(contractManager.client.storeObject(
            nonNullArgThat(ByteArrayInputStream(bytes)) { stream -> stream.readAllBytes().contentEquals(bytes).also { stream.reset() } },
            eq(audience), sha256 = eq(false), loHash = eq(false))
        ).thenReturn(
            Common.Location.getDefaultInstance()
        )

        permissionUpdater.saveConstructorArguments()

        verify(contractManager.client, times(1)).storeObject(
            nonNullArgThat(ByteArrayInputStream(bytes)) { stream -> stream.readAllBytes().contentEquals(bytes).also { stream.reset() } },
            eq(audience),
            sha256 = eq(true), // properly detected sha256
            loHash = eq(false) // properly detected full hash
        )
    }

    @Test
    fun `PermissionUpdater properly handles sha256 loBytes hash saving`() {
        val bytes = Random.nextBytes(1000)
        val sha256LoHash = bytes.sha256().loBytes().toByteArray().base64String()

        val records = listOf(Contracts.Fact.newBuilder()
            .apply {
                dataLocationBuilder.refBuilder.hash = sha256LoHash
            }
            .build())
        `when`(contractManager.client.loadObject(sha256LoHash)).thenReturn(bytes)

        val permissionUpdater = getPermissionUpdater(records)

        `when`(contractManager.client.storeObject(
            nonNullArgThat(ByteArrayInputStream(bytes)) { stream -> stream.readAllBytes().contentEquals(bytes).also { stream.reset() } },
            eq(audience), sha256 = eq(false), loHash = eq(false))
        ).thenReturn(
            Common.Location.getDefaultInstance()
        )

        permissionUpdater.saveConstructorArguments()

        verify(contractManager.client, times(1)).storeObject(
            nonNullArgThat(ByteArrayInputStream(bytes)) { stream -> stream.readAllBytes().contentEquals(bytes).also { stream.reset() } },
            eq(audience),
            sha256 = eq(true), // properly detected sha256
            loHash = eq(true) // properly detected loBytes hash (first 16 bytes)
        )
    }

    @Test
    fun `PermissionUpdater properly handles sha512 loBytes hash saving`() {
        val bytes = Random.nextBytes(1000)
        val sha256LoHash = bytes.sha512().loBytes().toByteArray().base64String()

        val records = listOf(Contracts.Fact.newBuilder()
            .apply {
                dataLocationBuilder.refBuilder.hash = sha256LoHash
            }
            .build())
        `when`(contractManager.client.loadObject(sha256LoHash)).thenReturn(bytes)

        val permissionUpdater = getPermissionUpdater(records)

        `when`(contractManager.client.storeObject(
            nonNullArgThat(ByteArrayInputStream(bytes)) { stream -> stream.readAllBytes().contentEquals(bytes).also { stream.reset() } },
            eq(audience), sha256 = eq(false), loHash = eq(false))
        ).thenReturn(
            Common.Location.getDefaultInstance()
        )

        permissionUpdater.saveConstructorArguments()

        verify(contractManager.client, times(1)).storeObject(
            nonNullArgThat(ByteArrayInputStream(bytes)) { stream -> stream.readAllBytes().contentEquals(bytes).also { stream.reset() } },
            eq(audience),
            sha256 = eq(false), // properly detected sha256
            loHash = eq(true) // properly detected loBytes hash (first 16 bytes)
        )
    }
}
