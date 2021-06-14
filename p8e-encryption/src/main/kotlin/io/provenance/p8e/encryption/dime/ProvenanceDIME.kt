package io.provenance.p8e.encryption.dime

import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import io.provenance.p8e.encryption.aes.ProvenanceAESCrypt
import io.provenance.p8e.encryption.ecies.ECUtils
import io.provenance.p8e.encryption.ecies.ECUtils.curveName
import io.provenance.p8e.encryption.ecies.ProvenanceECIESCryptogram
import io.provenance.p8e.encryption.ecies.ProvenanceKeyGenerator
import io.provenance.p8e.encryption.experimental.aes.aesDecryptStream
import io.provenance.p8e.encryption.experimental.extensions.aesDecrypt
import io.provenance.p8e.encryption.experimental.extensions.toSecretKeySpec
import io.provenance.p8e.encryption.model.DIMEAdditionalAuthenticationModel
import io.provenance.p8e.encryption.model.DIMEDekPayloadModel
import io.provenance.p8e.encryption.model.DIMEProcessingModel
import io.provenance.p8e.encryption.model.DIMEStreamProcessingModel
import io.p8e.proto.Util
import io.provenance.p8e.encryption.ecies.ProvenanceECIESCipher
import io.provenance.p8e.encryption.model.KeyProviders.DATABASE
import io.provenance.p8e.encryption.model.KeyRef
import io.provenance.proto.encryption.EncryptionProtos
import io.provenance.proto.encryption.EncryptionProtos.Audience
import io.provenance.proto.encryption.EncryptionProtos.Payload
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.InvalidKeySpecException
import java.time.OffsetDateTime
import java.util.Base64
import java.util.Properties
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

object ProvenanceDIME {

    const val PROCESSING_KEYS = "PROCESSING_KEYS"
    const val PROPOSER_E2EE_PUBLIC_KEY = "PROPOSER_E2EE_PUBLIC_KEY"
    val logger: Logger = LoggerFactory.getLogger(ProvenanceKeyGenerator::class.java)

    private fun convertPayloadToDIMEPayload(payloadId: Int,
                                            payloadText: String,
                                            additionalAuthenticatedData: String = "",
                                            key: SecretKeySpec,
                                            legacyEncoding: Boolean = true
    ): Payload {

        var cipherText = ProvenanceAESCrypt.encrypt(
                plainText = payloadText.toByteArray(Charsets.UTF_8),
                additionalAuthenticatedData = additionalAuthenticatedData,
                key = key,
                useZeroIV = false)
                ?: throw IllegalArgumentException("The payloadText cannot be encrypted")

        if (legacyEncoding) {
            cipherText = BaseEncoding.base64().encode(cipherText).toByteArray(Charsets.UTF_8)
        }

        //convert it to Payload
        return createPayload(payloadId, cipherText)
    }

    /**
     * A handy testing/utility service to quick DIME
     */
    fun mockProcessingAudienceList() = (0..10).map { ProvenanceKeyGenerator.generateKeyPair() }

    fun mockProcessingAudienceListPrivateKey(peerPublicKey: ByteString, processingAudienceList: List<KeyPair>): PrivateKey {
        processingAudienceList.find {
            ECUtils.publicKeyEncoded(it.public) == peerPublicKey.toStringUtf8()
        }?.let {
            return it.private
        }
        throw IllegalArgumentException("private key not found for ${peerPublicKey.toStringUtf8()}")
    }

    fun createPayload(payloadId: Int, payloadText: ByteArray?): Payload {
        return Payload.newBuilder().setId(payloadId)
                .setCipherText(ByteString.copyFrom(payloadText))
                .build()
    }

    fun createAudience(contextType: EncryptionProtos.ContextType, encryptedDEK: ByteArray, publicKey: ByteArray,
                       payloadId: Int, ephemeralPubKey: PublicKey, tag: ByteArray): Audience {

        return Audience.newBuilder()
                .setContext(contextType)
                .setEncryptedDek(ByteString.copyFrom(encryptedDEK))
                .setPayloadId(payloadId)
                .setPublicKey(ByteString.copyFrom(publicKey))
                .setEphemeralPubkey(ByteString.copyFrom(BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(ephemeralPubKey)).toByteArray(Charsets.UTF_8)))
                .setTag(ByteString.copyFrom(tag))
                .build()
    }

    fun getOwnerAudience(ownerEncryptionKeyRef: KeyRef, additionalAuthenticatedData: String = "", key: SecretKeySpec, payloadId: Int): Audience {
        val (publicKeyEncodedStrForOwner, provenanceECIESCryptogramForOwner) = getECIESEncodedPayload(ownerEncryptionKeyRef, additionalAuthenticatedData, key)
        return createAudience(contextType = EncryptionProtos.ContextType.SUBMISSION,
                ephemeralPubKey = provenanceECIESCryptogramForOwner.ephemeralPublicKey!!,
                encryptedDEK = BaseEncoding.base64().encode(provenanceECIESCryptogramForOwner.encryptedData).toByteArray(Charsets.UTF_8),
                publicKey = publicKeyEncodedStrForOwner.toByteArray(Charsets.UTF_8),
                payloadId = payloadId,
                tag = BaseEncoding.base64().encode(provenanceECIESCryptogramForOwner.tag).toByteArray(Charsets.UTF_8))
    }

    fun getECIESEncodedPayload(encryptionKeyRef: KeyRef, additionalAuthenticatedData: String = "", key: SecretKeySpec): Pair<String, ProvenanceECIESCryptogram> {
        val publicKeyEncodedStr = BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(encryptionKeyRef.publicKey))

        // Encrypt any encryptionKeyRef with a null UUID the legacy way, as we do not know if the Affiliate will be a
        // SmartKey managed key.
        val provenanceECIESCryptogram = if(encryptionKeyRef.uuid == null || encryptionKeyRef.type == DATABASE) {
            ProvenanceECIESCipher().encrypt(
                BaseEncoding.base64().encode(key.encoded).toByteArray(Charsets.UTF_8),
                encryptionKeyRef.publicKey,
                additionalAuthenticatedData
            )
        } else {
            ProvenanceECIESCipher().encrypt(
                BaseEncoding.base64().encode(key.encoded).toByteArray(Charsets.UTF_8),
                encryptionKeyRef.publicKey,
                encryptionKeyRef.uuid.toString(),
                additionalAuthenticatedData
            )
        }
        return Pair(publicKeyEncodedStr, provenanceECIESCryptogram)
    }

    fun getDEKFromDIME(dime: EncryptionProtos.DIME, encryptionKeyRef: KeyRef, dimeAdditionalAuthenticationModel: DIMEAdditionalAuthenticationModel = DIMEAdditionalAuthenticationModel()): DIMEDekPayloadModel {
        val dek = getDEK(dime.audienceList, encryptionKeyRef, dimeAdditionalAuthenticationModel.dekAdditionalAuthenticatedData)
        val decrypted = decryptDIME(dime, dek, dimeAdditionalAuthenticationModel.payloadAdditionalAuthenticatedData)
        return DIMEDekPayloadModel(dek.toString(Charsets.UTF_8), decrypted.toString(Charsets.UTF_8))
    }

    fun getDEK(audienceList: List<Audience>, encryptionKeyRef: KeyRef, additionalAuthenticatedData: String = ""): ByteArray {
        val audience = getAudience(audienceList, encryptionKeyRef.publicKey)

        val provenanceECIESCryptogram = ECUtils.getProvenanceCryptogram(
                audience.ephemeralPubkey.toString(Charsets.UTF_8),
                audience.tag.toString(Charsets.UTF_8),
                audience.encryptedDek.toString(Charsets.UTF_8),
                encryptionKeyRef.publicKey.curveName())

        return if(encryptionKeyRef.type == DATABASE) {
            ProvenanceECIESCipher().decrypt(provenanceECIESCryptogram, encryptionKeyRef.privateKey!!, additionalAuthenticatedData)
        } else {
            ProvenanceECIESCipher().decrypt(provenanceECIESCryptogram, encryptionKeyRef.uuid.toString(), additionalAuthenticatedData)
        }
    }

    @Throws(IllegalStateException::class)
    fun getAudience(audienceList: List<Audience>, publicKey: PublicKey): Audience {
        val publicKeyString = BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(publicKey))
        return audienceList.firstOrNull { publicKeyString == it.publicKey.toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("Audience list does not contain Audience of member..")
    }

    fun decryptDIME(dime: EncryptionProtos.DIME, DEK: ByteArray, additionalAuthenticatedData: String = ""): ByteArray {
        return decryptPayload(dime.getPayload(0), DEK, additionalAuthenticatedData)
    }

    // Created to avoid an import of core-extensions.
    private val unbase64: (ByteArray) -> ByteArray = { Base64.getDecoder().decode(it) }

    fun decryptPayload(payload: Payload, DEK: ByteArray, additionalAuthenticatedData: String = ""): ByteArray {
        val key = unbase64(DEK).toSecretKeySpec()

        // If the cipherText fails decryption, base64 decode it and try again.
        // * The legacy dime code places the base64 encoded cipherText into the Payload object.
        // * The current dime code placed the raw bytes into the Payload.cipherText object.
        //    * See src/test/resources/test-data.json
        // NOTE:
        //   Order doesn't _really_ matter here (over 10k iterations the difference between the two ops is negligible).
        val bytes = payload.cipherText.toByteArray()
        val decryptor = { it: ByteArray -> it.aesDecrypt(key, aad = additionalAuthenticatedData.toByteArray()) }
        return try {
            decryptor(bytes)
        } catch (e: IllegalArgumentException) {
            // Legacy handling: cipherText was base64 encoded.
            decryptor(unbase64(bytes))
        }
    }

    fun decryptPayload(stream: InputStream, DEK: ByteArray, additionalAuthenticatedData: String = ""): InputStream {
        val key = unbase64(DEK).toSecretKeySpec()
        return aesDecryptStream(stream, key, aad = additionalAuthenticatedData.toByteArray())
    }

    /**
     * Get all audience keys from KMS(Mocked for now) TODO move out to it's own utility?
     */
    @Throws(InvalidKeySpecException::class)
    fun getMockProcessingAudienceKeys(): List<PublicKey> {
        val properties = Properties()
        properties.load(this::class.java.getResourceAsStream("/peer-certs/certs.properties"))

        val listOfAudienceKeys = mutableListOf<PublicKey>()
        properties.forEach { (k, v) ->
            if (k.toString().startsWith("public-key")) {
                val publicKey = ECUtils.convertBytesToPublicKey(ECUtils.decodeString(v.toString()))
                listOfAudienceKeys.add(publicKey)
            }
        }
        return listOfAudienceKeys
    }

    fun getMockProcessingAudiencePrivateKey(publicKey: PublicKey): PrivateKey {
        var keyValue: String
        val props = Properties().apply {
            load(ProvenanceDIME::class.java.getResourceAsStream("/peer-certs/certs.properties"))
        }
        props.forEach { (k, v) ->
            if (v == BaseEncoding.base64().encode(ECUtils.convertPublicKeyToBytes(publicKey))) {
                keyValue = (k as String).replace("public", "private")
                return ECUtils.privateKeyFromPaddedByteArray(props.getProperty(keyValue))
            }
        }
        throw IllegalArgumentException("No Private Key Found for Public Key")
    }

    /**
     * Constructs a DIME with encrypted payloads from the given arguments
     *
     * @param dimeId UUID optional
     * @param payloadId Int payload ID 0 is used for the primary payload by convention. other IDs are used to contain additionalDEKS. Unless you know what you're doing, leave this as 0
     * @param payloadText String unencrypted JSON string containing payload data
     * @param additionalAuthenticatedData String TODO
     * @param ownerTransactionCert PublicKey public key for DIME owner
     * @param metadata Map String to String map of unencrypted data to be provided outside the payload itself (ex: assetUuid). Primarily used on chain only
     * @param additionalAudience Map of ContextType to Set of Public Keys for additional members who should be able to decrypt or otherwise manipulate the DIME
     * @param additionalAudienceAuthenticatedData Map TODO
     * @param additionalDEKS Map of String to SecretKeySpec, used by chaincode to fetch and decrypt additional DIMEs already stored on chain
     * @param additionalDEKSAuthenticatedData Map TODO
     * @param processingAudienceKeys List of PublicKey, used by individual chaincode containers to decrypt the DIME for processing NOTE: these are never stored within the DIME itself
     * @param legacyEncoding Original Kotlin DIME creation wrapped the encrypted payload with an additional layer of Base64 encoding. Node does not. At some point, this flag will be removed and the additional encoding will be skipped for all use cases.
     */
    fun createDIME(dimeId: UUID = UUID.randomUUID(),
                   payloadId: Int = 0,
                   payloadText: String,
                   additionalAuthenticatedData: String = "",
                   ownerEncryptionKeyRef: KeyRef,
                   metadata: Map<String, String> = emptyMap(),
                   additionalAudience: Map<EncryptionProtos.ContextType, Set<KeyRef>> = emptyMap(),
                   additionalAudienceAuthenticatedData: Map<EncryptionProtos.ContextType, Set<Pair<PublicKey, String>>> = emptyMap(),
                   additionalDEKS: Map<String, SecretKeySpec> = emptyMap(),
                   additionalDEKSAuthenticatedData: Map<String, String> = emptyMap(),
                   processingAudienceKeys: List<KeyRef>,
                   providedDEK: SecretKeySpec? = null,
                   legacyEncoding: Boolean = true
    ): DIMEProcessingModel {

        val messageDIME = EncryptionProtos.DIME.newBuilder()
        val key = providedDEK.createIfNotProvided()
        val payload = convertPayloadToDIMEPayload(payloadId, payloadText, additionalAuthenticatedData, key, legacyEncoding)
        messageDIME.addPayload(payload)

        val audienceList = mutableListOf<Audience>()
        val processingScopedAudienceList = mutableListOf<Audience>()
        processingAudienceKeys.forEach { keyRef ->
            val (publicKeyEncodedStr, provenanceECIESCryptogram) = getECIESEncodedPayload(keyRef, additionalAuthenticatedData, key)
              val audience = createAudience(contextType = EncryptionProtos.ContextType.PROCESSING
                    , ephemeralPubKey = provenanceECIESCryptogram.ephemeralPublicKey
                    , encryptedDEK = BaseEncoding.base64().encode(provenanceECIESCryptogram.encryptedData).toByteArray(Charsets.UTF_8)
                    , publicKey = publicKeyEncodedStr.toByteArray(Charsets.UTF_8)
                    , payloadId = payloadId
                    , tag = BaseEncoding.base64().encode(provenanceECIESCryptogram.tag).toByteArray(Charsets.UTF_8))

            processingScopedAudienceList.add(audience)
        }

        additionalAudience.forEach { additionalAudienceEncryptionKeyRef ->
            additionalAudienceEncryptionKeyRef.value.forEach { keyRef ->
                val additionalAudienceAAD = additionalAudienceAuthenticatedData[additionalAudienceEncryptionKeyRef.key]?.find { aadData -> aadData.first == keyRef.publicKey }?.second
                val (publicKeyEncodedStr, provenanceECIESCryptogram) = getECIESEncodedPayload(keyRef, additionalAudienceAAD ?: "", key)
                val audience = createAudience(contextType = additionalAudienceEncryptionKeyRef.key
                        , ephemeralPubKey = provenanceECIESCryptogram.ephemeralPublicKey
                        , encryptedDEK = BaseEncoding.base64().encode(provenanceECIESCryptogram.encryptedData).toByteArray(Charsets.UTF_8)
                        , publicKey = publicKeyEncodedStr.toByteArray(Charsets.UTF_8)
                        , payloadId = payloadId
                        , tag = BaseEncoding.base64().encode(provenanceECIESCryptogram.tag).toByteArray(Charsets.UTF_8))

                audienceList.add(audience)
            }
        }

        //starts from 1 since the actual payload should occupy..if we do partial encryption i.e parts of payload being owened by different owners..then this should change
        var payloadIdForDEKS = 1
        additionalDEKS.forEach { x ->
            run {
                val additionalDEKAAD = additionalDEKSAuthenticatedData[x.key]

                processingAudienceKeys.forEach { keyRef ->
                    val (publicKeyEncodedStr, provenanceECIESCryptogram) = getECIESEncodedPayload(keyRef, additionalDEKAAD ?: "", key)
                    val audience = createAudience(contextType = EncryptionProtos.ContextType.PROCESSING
                            , ephemeralPubKey = provenanceECIESCryptogram.ephemeralPublicKey
                            , encryptedDEK = BaseEncoding.base64().encode(provenanceECIESCryptogram.encryptedData).toByteArray(Charsets.UTF_8)
                            , publicKey = publicKeyEncodedStr.toByteArray(Charsets.UTF_8)
                            , payloadId = payloadIdForDEKS
                            , tag = BaseEncoding.base64().encode(provenanceECIESCryptogram.tag).toByteArray(Charsets.UTF_8))

                    processingScopedAudienceList.add(audience)
                }

                val p = convertPayloadToDIMEPayload(payloadIdForDEKS, x.key, additionalDEKAAD
                        ?: "", x.value, legacyEncoding)

                messageDIME.addPayload(p)
            }
            payloadIdForDEKS++
        }

        val owner = getOwnerAudience(ownerEncryptionKeyRef, additionalAuthenticatedData, key, payloadId)

        //add the owner to the audience.. ??
        audienceList.add(owner)

        val dime = messageDIME
                .addAllAudience(audienceList)
                .setOwner(owner)
                .setUuid(Util.UUID.newBuilder().setValue(dimeId.toString()).build())
                .putAllMetadata(metadata)
                .setAuditFields(Util.AuditFields.newBuilder()
                        .setCreatedDate(Timestamp.getDefaultInstance()
                                .newBuilderForType().setSeconds(OffsetDateTime.now().toEpochSecond())
                                .build())
                        .setUpdatedDate(Timestamp.getDefaultInstance()
                                .newBuilderForType().setSeconds(OffsetDateTime.now().toEpochSecond()))
                        .setVersion(1)
                        .build())
                .build()
        return DIMEProcessingModel(dime, processingScopedAudienceList)
    }

    fun createDIME(dimeId: UUID = UUID.randomUUID(),
                   payloadId: Int = 0,
                   payload: InputStream,
                   additionalAuthenticatedData: String = "",
                   ownerEncryptionKeyRef: KeyRef,
                   metadata: Map<String, String> = emptyMap(),
                   additionalAudience: Map<EncryptionProtos.ContextType, Set<KeyRef>> = emptyMap(),
                   additionalAudienceAuthenticatedData: Map<EncryptionProtos.ContextType, Set<Pair<PublicKey, String>>> = emptyMap(),
                   processingAudienceKeys: List<KeyRef>,
                   providedDEK: SecretKeySpec? = null
    ): DIMEStreamProcessingModel {

        val messageDIME = EncryptionProtos.DIME.newBuilder()
        val key = providedDEK.createIfNotProvided()

        val audienceList = mutableListOf<Audience>()
        val processingScopedAudienceList = mutableListOf<Audience>()

        val encryptedPayload = ProvenanceAESCrypt.encrypt(
                payload,
                key = key,
                useZeroIV = false
        )

        processingAudienceKeys.forEach { keyRef ->
            val (publicKeyEncodedStr, provenanceECIESCryptogram) = getECIESEncodedPayload(keyRef, additionalAuthenticatedData, key)
            val audience = createAudience(contextType = EncryptionProtos.ContextType.PROCESSING
                    , ephemeralPubKey = provenanceECIESCryptogram.ephemeralPublicKey
                    , encryptedDEK = BaseEncoding.base64().encode(provenanceECIESCryptogram.encryptedData).toByteArray(Charsets.UTF_8)
                    , publicKey = publicKeyEncodedStr.toByteArray(Charsets.UTF_8)
                    , payloadId = payloadId
                    , tag = BaseEncoding.base64().encode(provenanceECIESCryptogram.tag).toByteArray(Charsets.UTF_8))

            processingScopedAudienceList.add(audience)
        }

        additionalAudience.forEach {
            it.value.forEach { keyRef ->
                val additionalAudienceAAD = additionalAudienceAuthenticatedData[it.key]?.find { aadData -> aadData.first == keyRef.publicKey }?.second
                val (publicKeyEncodedStr, provenanceECIESCryptogram) = getECIESEncodedPayload(keyRef, additionalAudienceAAD ?: "", key)
                val audience = createAudience(
                    contextType = it.key,
                    ephemeralPubKey = provenanceECIESCryptogram.ephemeralPublicKey,
                    encryptedDEK = BaseEncoding.base64().encode(provenanceECIESCryptogram.encryptedData)
                        .toByteArray(Charsets.UTF_8),
                    publicKey = publicKeyEncodedStr.toByteArray(Charsets.UTF_8),
                    payloadId = payloadId,
                    tag = BaseEncoding.base64().encode(provenanceECIESCryptogram.tag).toByteArray(Charsets.UTF_8)
                )
                audienceList.add(audience)
            }
        }

        val owner = getOwnerAudience(ownerEncryptionKeyRef, additionalAuthenticatedData, key, payloadId)

        //add the owner to the audience.. ??
        audienceList.add(owner)

        val dime = messageDIME
                .addAllAudience(audienceList)
                .setOwner(owner)
                .setUuid(Util.UUID.newBuilder().setValue(dimeId.toString()).build())
                .putAllMetadata(metadata)
                .addPayload(Payload.newBuilder().build()) // Have to place an empty payload or else other code breaks on assumption there's a 0th indexed payload
                .setAuditFields(Util.AuditFields.newBuilder()
                        .setCreatedDate(Timestamp.getDefaultInstance()
                                .newBuilderForType().setSeconds(OffsetDateTime.now().toEpochSecond())
                                .build())
                        .setUpdatedDate(Timestamp.getDefaultInstance()
                                .newBuilderForType().setSeconds(OffsetDateTime.now().toEpochSecond()))
                        .setVersion(1)
                        .build())
                .build()
        return DIMEStreamProcessingModel(dime, processingScopedAudienceList, encryptedPayload)
    }

    private fun SecretKeySpec?.createIfNotProvided() = this.takeIf { it != null } ?: ProvenanceAESCrypt.secretKeySpecGenerate()

    fun getSecretKeyFromDIME(dime: EncryptionProtos.DIME, encryptionKeyRef: KeyRef): SecretKeySpec =
            ProvenanceAESCrypt.secretKeySpecGenerate(Base64.getDecoder().decode(getDEKFromDIME(dime, encryptionKeyRef).dek))

}
