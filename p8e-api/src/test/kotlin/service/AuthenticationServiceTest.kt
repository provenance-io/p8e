package service

import com.nhaarman.mockitokotlin2.any
import helper.TestUtils
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import io.p8e.proto.Affiliate
import io.p8e.proto.Authentication
import io.p8e.proto.AuthenticationServiceGrpc
import io.p8e.util.toHex
import io.p8e.util.toPublicKeyProto
import io.p8e.util.toByteString
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.engine.grpc.v1.AuthenticationGrpc
import io.provenance.engine.service.AuthenticationService
import io.provenance.p8e.shared.domain.AffiliateRecord
import io.provenance.p8e.shared.domain.AffiliateTable
import io.provenance.p8e.shared.service.AffiliateService
import io.provenance.p8e.shared.util.KeyClaims
import io.provenance.p8e.shared.util.TokenManager
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.security.KeyPair

@Suppress("UNCHECKED_CAST")
class AuthenticationServiceTest {

    lateinit var affiliateRecord: AffiliateRecord

    lateinit var request: Authentication.AuthenticationRequest

    lateinit var affiliateServiceMock: AffiliateService

    lateinit var authenticationService: AuthenticationService

    lateinit var tokenManager: TokenManager<KeyClaims>

    lateinit var keyPair: KeyPair

    lateinit var signature: ByteArray

    lateinit var blockingStub: AuthenticationServiceGrpc.AuthenticationServiceBlockingStub

    lateinit var token: Authentication.AuthenticationToken

    lateinit var testJwtToken: Authentication.Jwt

    @Before
    fun setupAuthRequest() {
        //Setup
        TestUtils.DatabaseConnect()
        keyPair = TestUtils.generateKeyPair()

        //Setup the AffiliateTable with test data.
        transaction {
             SchemaUtils.create(AffiliateTable)
             affiliateRecord = AffiliateRecord.new {
                    publicKey = EntityID(keyPair.public.toHex(), AffiliateTable)
                    active = true
                    privateKey = keyPair.private.toHex()
                    whitelistData = Affiliate.AffiliateWhitelist.newBuilder().build()
                    encryptionPublicKey = keyPair.public.toHex()
                    encryptionPrivateKey = keyPair.private.toHex()
                    indexName = "test"
                    alias = "test"
                }
        }

        //Generate valid token with a comfortable expiration time
        token = TestUtils.generateAuthenticationToken(30)

        signature = TestUtils.generateJavaSecuritySignature(keyPair.private, token)

        testJwtToken = Authentication.Jwt.newBuilder()
            .setToken("some-token")
            .build()

        //Mock services
        affiliateServiceMock = Mockito.mock(AffiliateService::class.java)
        tokenManager = Mockito.mock(TokenManager::class.java) as TokenManager<KeyClaims>
        authenticationService = Mockito.mock(AuthenticationService::class.java)

        //TODO: Should be a @Rule, but causing problems, need to investigate.
        val grpcCleanup = GrpcCleanupRule()

        //Setup Grpc Test Server
        val testServer = InProcessServerBuilder.generateName()
        grpcCleanup.register(
            InProcessServerBuilder
                .forName(testServer)
                .directExecutor()
                .addService(AuthenticationGrpc(AuthenticationService(tokenManager, affiliateServiceMock)))
                .build()
                .start()
        )

        //Stub the test server against the AuthenticationService
        blockingStub = AuthenticationServiceGrpc.newBlockingStub(
            grpcCleanup.register(InProcessChannelBuilder.forName(testServer).directExecutor().build())
        )
    }

    @Test
    fun `Valid authentication by receiving a JWT Token`() {
        //Setup
        request = Authentication.AuthenticationRequest.newBuilder()
            .setToken(token)
            .setSignature(signature.toByteString())
            .setPublicKey(keyPair.public.toPublicKeyProto())
            .build()

        Mockito.`when`(affiliateServiceMock.get(keyPair.public)).thenReturn(affiliateRecord)

        // Not testing tokenManager in this scenario
        Mockito.`when`(tokenManager.create(any())).thenReturn(testJwtToken)

        //Validate
        val jwtToken = blockingStub.authenticate(request)
        Assert.assertSame(testJwtToken, jwtToken)
    }

    @Test(expected = StatusRuntimeException::class)
    fun `Validate authentication by not receiving a JWT Token`() {
        // set token that will/has expired before validation
        token = TestUtils.generateAuthenticationToken(0)

        request = Authentication.AuthenticationRequest.newBuilder()
            .setToken(token)
            .setSignature(signature.toByteString())
            .setPublicKey(keyPair.public.toPublicKeyProto())
            .build()

        Mockito.`when`(affiliateServiceMock.get(keyPair.public)).thenReturn(affiliateRecord)

        // Not testing tokenManager in this scenario
        Mockito.`when`(tokenManager.create(any())).thenReturn(testJwtToken)

        //Validate
        blockingStub.authenticate(request)
    }

    @Test(expected = StatusRuntimeException::class)
    fun `Validate authentication by not sending a signature`() {
        //Mock the affiliateService to return our test affiliate record.
        Mockito.`when`(affiliateServiceMock.get(keyPair.public)).thenReturn(affiliateRecord)

        // Not testing tokenManager in this scenario
        Mockito.`when`(tokenManager.create(any())).thenReturn(testJwtToken)

        //Don't set signature
        request = Authentication.AuthenticationRequest.newBuilder()
            .setToken(token)
            .setPublicKey(keyPair.public.toPublicKeyProto())
            .build()

        blockingStub.authenticate(request)
    }

    @Test(expected = StatusRuntimeException::class)
    fun `Validate authentication with a bad public key`() {
        //Setup
        val newKeyPair = ProvenanceKeyGenerator.generateKeyPair()

        request = Authentication.AuthenticationRequest.newBuilder()
            .setToken(token)
            .setSignature(signature.toByteString())
            .setPublicKey(newKeyPair.public.toPublicKeyProto())
            .build()

        Mockito.`when`(affiliateServiceMock.get(keyPair.public)).thenReturn(affiliateRecord)

        // Not testing tokenManager in this scenario
        Mockito.`when`(tokenManager.create(any())).thenReturn(testJwtToken)

        //Validate
        blockingStub.authenticate(request)
    }

    @Test(expected = StatusRuntimeException::class)
    fun `Validate authentication with no associated affiliate`() {
        // Not testing tokenManager in this scenario
        Mockito.`when`(tokenManager.create(any())).thenReturn(testJwtToken)

        request = Authentication.AuthenticationRequest.newBuilder()
            .setToken(token)
            .setSignature(signature.toByteString())
            .setPublicKey(keyPair.public.toPublicKeyProto())
            .build()

        //Do not mock affiliateService for test affiliateRecord

        //Validate
        blockingStub.authenticate(request)
    }
}
