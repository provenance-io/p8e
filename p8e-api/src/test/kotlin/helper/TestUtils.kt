package helper

import io.p8e.grpc.Constant
import io.p8e.proto.Authentication
import io.p8e.util.toByteString
import io.p8e.util.toProtoTimestampProv
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import org.jetbrains.exposed.sql.Database
import java.security.PrivateKey
import java.security.Signature
import java.time.OffsetDateTime
import java.util.*

class TestUtils {
    companion object {

        fun DatabaseConnect(){
            Database.connect(
                url = listOf(
                    "jdbc:h2:mem:test",
                    "DB_CLOSE_DELAY=-1",
                    "LOCK_TIMEOUT=10000",
                    "INIT=" + listOf(
                        "create domain if not exists jsonb as other",
                        "create domain if not exists TIMESTAMPTZ as TIMESTAMP WITH TIME ZONE"
                    ).joinToString("\\;")
                ).joinToString(";") + ";",
                driver = "org.h2.Driver"
            )
        }

        fun generateKeyPair() = ProvenanceKeyGenerator.generateKeyPair()

        fun generateAuthenticationToken(expirationSec: Long): Authentication.AuthenticationToken =
            Authentication.AuthenticationToken.newBuilder()
                .setRandomData(UUID.randomUUID().toString().toByteString())
                .setExpiration(OffsetDateTime.now().plusSeconds(expirationSec).toProtoTimestampProv())
                .build()

        fun generateJavaSecuritySignature(privateKey: PrivateKey, token: Authentication.AuthenticationToken) =
            Signature.getInstance(Constant.JWT_ALGORITHM).apply {
                initSign(privateKey)
                update(token.toByteArray())
            }.let {
                it.sign()
            }

    }
}
