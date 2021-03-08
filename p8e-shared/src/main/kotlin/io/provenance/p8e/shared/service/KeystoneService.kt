package io.provenance.p8e.shared.service

import com.fasterxml.jackson.databind.ObjectMapper
import feign.*
import feign.jackson.JacksonDecoder
import feign.jackson.JacksonEncoder
import io.p8e.util.base64String
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey
import java.security.PublicKey

data class KeystoneAddress(
    val value: String,
    val type: String
)

data class KeystonePublicKey(
    val encodedKey: String,
    val curve: String,
    val encoding: KeystoneKeyEncoding,
    val compressed: Boolean,
    val address: KeystoneAddress? = null
)

data class KeystoneBalance(
    val denom: String,
    val amount: String
)

enum class KeystoneKeyEncoding {
    RAW
}

enum class KeystoneKeyUsage {
    CONTRACT,
    SERVICE
}

data class KeystoneKey(
    val id: String,
    val publicKey: KeystonePublicKey,
    val keyUsage: KeystoneKeyUsage,
    val balance: KeystoneBalance?
)

data class KeystoneKeyRegisterRequest(
    val publicKey: KeystonePublicKey,
    val keyUsage: KeystoneKeyUsage
)

interface Keystone {
    @RequestLine("GET /")
    @Headers("authorization: Bearer {jwt}", "Content-Type: application/json")
    fun getUserRegisteredKeys(@Param("jwt") jwt: String): Array<KeystoneKey>

    @RequestLine("POST /")
    @Headers("authorization: Bearer {jwt}", "Content-Type: application/json")
    fun registerKey(@Param("jwt") jwt: String, registerRequest: KeystoneKeyRegisterRequest): KeystoneKey
}

class KeystoneService(
    private val objectMapper: ObjectMapper,
    private val keystoneUrl: String
) {
    private val keystone = Feign.builder()
        .encoder(JacksonEncoder(objectMapper))
        .decoder(JacksonDecoder(objectMapper))
        .logLevel(Logger.Level.FULL)
        .target(Keystone::class.java, keystoneUrl)

    fun getUserRegisteredKeys(jwt: String) = keystone.getUserRegisteredKeys(jwt)

    fun registerKey(jwt: String, key: PublicKey, curve: String, keyUsage: KeystoneKeyUsage) = keystone.registerKey(jwt, KeystoneKeyRegisterRequest(
        KeystonePublicKey(
            (key as BCECPublicKey).q.getEncoded(true).base64String(),
            curve,
            KeystoneKeyEncoding.RAW,
            true
        ),
        keyUsage
    ))
}
