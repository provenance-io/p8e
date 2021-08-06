package io.provenance.os.domain.inputstream

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.p8e.crypto.SignatureInputStream
import io.p8e.crypto.SignerImpl
import io.p8e.crypto.verify
import io.p8e.util.base64String
import io.provenance.p8e.encryption.dime.ProvenanceDIME
import io.provenance.p8e.encryption.util.ByteUtil
import io.provenance.p8e.encryption.util.ByteUtil.writeUInt16
import io.provenance.p8e.encryption.util.ByteUtil.writeUInt32
import io.p8e.util.configureProvenance
import io.p8e.util.toHex
import io.provenance.p8e.encryption.util.HashingCipherInputStream
import io.provenance.os.domain.Signature
import io.provenance.os.util.CertificateUtil
import io.provenance.os.util.orThrow
import io.provenance.p8e.encryption.model.KeyRef
import io.provenance.proto.encryption.EncryptionProtos.DIME
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.util.UUID

class DIMEInputStream(
    val dime: DIME,
    `in`: InputStream,
    val metadata: Map<String, String> = mapOf(),
    val uuid: UUID = UUID.randomUUID(),
    val uri: String = "",
    val signatures: List<Signature> = listOf(),
    val internalHash: Boolean = true,
    val externalHash: Boolean = true
) : FilterInputStream(BufferedInputStream(`in`)) {

    private val internalInputStream = `in`

    private val uuidBytes = uuid.let {
        val bb = ByteBuffer.wrap(ByteArray(16))
        bb.putLong(it.mostSignificantBits)
        bb.putLong(it.leastSignificantBits)
        bb.array()
    }
    private val uriBytes = uri.toByteArray()
    private val metadataBytes = OBJECT_MAPPER.writeValueAsBytes(metadata)
    private val dimeBytes = dime.toByteArray()
    val signaturesBytes = OBJECT_MAPPER.writeValueAsBytes(signatures)

    private val header = ByteArray(4 + 2 + 4 + uuidBytes.size + 4 + metadataBytes.size + 4 + uriBytes.size + 4 + signaturesBytes.size + 4 + dimeBytes.size)
        .apply {
            // Magic Bytes
            writeUInt32(this, 0, MAGIC_BYTES)

            // Version
            writeUInt16(this, 4, VERSION)

            // UUID Bytes Length
            writeUInt32(this, 4 + 2, uuidBytes.size.toLong())

            // UUID Bytes
            System.arraycopy(uuidBytes, 0, this, 4 + 2 + 4, uuidBytes.size)

            // Metadata Bytes Length
            writeUInt32(this, 4 + 2 + 4 + uuidBytes.size, metadataBytes.size.toLong())

            // Metadata Bytes
            System.arraycopy(metadataBytes, 0, this, 4 + 2 + 4 + uuidBytes.size + 4, metadataBytes.size)

            // URI Bytes Length
            writeUInt32(this, 4 + 2 + 4 + uuidBytes.size + 4 + metadataBytes.size, uriBytes.size.toLong())

            // URI Bytes
            System.arraycopy(uriBytes, 0, this, 4 + 2 + 4 + uuidBytes.size + 4 + metadataBytes.size + 4, uriBytes.size)

            // Signatures Bytes
            writeUInt32(this, 4 + 2 + 4 + uuidBytes.size + 4 + metadataBytes.size + 4 + uriBytes.size, signaturesBytes.size.toLong())

            // Signatures Bytes
            System.arraycopy(signaturesBytes, 0, this, 4 + 2 + 4 + uuidBytes.size + 4 + metadataBytes.size + 4 + uriBytes.size + 4, signaturesBytes.size)

            // DIME Bytes Length
            writeUInt32(this, 4 + 2 + 4 + uuidBytes.size + 4 + metadataBytes.size + 4 + uriBytes.size + 4 + signaturesBytes.size, dimeBytes.size.toLong())

            // DIME Bytes
            System.arraycopy(dimeBytes, 0, this, 4 + 2 + 4 + uuidBytes.size + 4 + metadataBytes.size + 4 + uriBytes.size + 4 + signaturesBytes.size + 4, dimeBytes.size)
        }

    private val internalDigest = MessageDigest.getInstance("SHA-512")
    private val externalDigest = MessageDigest.getInstance("SHA-512")
        .apply {
            update(header, 0, header.size)
        }

    private var pos = 0

    fun getFirstSignaturePublicKey(): PublicKey {
        return signatures
            .firstOrNull()
            .orThrow { IllegalStateException("Signature is missing from object, has item been fetched from object store?") }
            .publicKey.toString(Charsets.UTF_8)
            .let(CertificateUtil::pemToPublicKey)
    }

    fun getFirstSignature(): ByteArray {
        return signatures
            .firstOrNull()
            .orThrow { IllegalStateException("Signature is missing from object, has item been fetched from object store?") }
            .signature
    }

    override fun read() =
        if (pos >= header.size) {
            val res = `in`.read()
            if (res != -1) {
                val bytes = ByteArray(1)
                bytes[0] = res.toByte()
                if (internalHash) {
                    internalDigest.update(bytes)
                }
                if (externalHash) {
                    externalDigest.update(bytes)
                }
                pos++
            }
            res
        } else {
            externalDigest.update(header, pos, 1)
            header[pos++].toInt() and 0xFF
        }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= header.size) {
            val res = `in`.read(b, off, len)
            if (res != -1) {
                pos += res
                if (internalHash) {
                    internalDigest.update(b, off, res)
                }
                if (externalHash) {
                    externalDigest.update(b, off, res)
                }
            }
            return res
        } else {
            if (pos + len > header.size) {
                val sizeLeftInHeader = header.size - pos
                System.arraycopy(header, pos, b, off, sizeLeftInHeader)
                val streamRead = `in`.read(b, off + sizeLeftInHeader, len - sizeLeftInHeader)
                val totalRead = sizeLeftInHeader + streamRead

                if (internalHash) {
                    internalDigest.update(b, off + sizeLeftInHeader, streamRead)
                }
                if (externalHash) {
                    externalDigest.update(b, off + sizeLeftInHeader, streamRead)
                }

                pos += totalRead
                return totalRead
            } else {
                System.arraycopy(header, pos, b, off, len)

                pos += len
                return len
            }
        }
    }

    /**
     * Retrieve the payload as a SignatureInputStream containing the CipherInputStream
     * in decrypt mode using the public key the item was stored with.
     *
     * @param keyPair - The encryption key pair to decrypt the stream
     * @return - SignatureInputStream containing a CipherInputStream in DECRYPT mode.
     */
    fun getDecryptedPayload(encryptionKeyRef: KeyRef): SignatureInputStream {
        // seek past the header
        pos += header.size

        return ProvenanceDIME.getDEK(dime.audienceList, encryptionKeyRef)
            .let { ProvenanceDIME.decryptPayload(this, it) }
            .verify(getFirstSignaturePublicKey(), getFirstSignature())
    }

    /**
     * Retrieve the payload as a SignatureInputStream containing the CipherInputStream
     * in decrypt mode using the public key the item was stored with.
     *
     * @param keyPair - The encryption key pair to decrypt the stream
     * @param signaturePublicKey - The signature public key to use for signature verification
     * @return - SignatureInputStream containing a CipherInputStream in DECRYPT mode.
     */
    fun getDecryptedPayload(encryptionKeyRef: KeyRef, signaturePublicKey: PublicKey): SignatureInputStream {
        // seek past the header
        pos += header.size

        val signatureToUse = signatures.find { it.publicKey.toString(Charsets.UTF_8) == CertificateUtil.publicKeyToPem(signaturePublicKey) }
            .orThrow { IllegalStateException("Unable to find signature in object for public key ${signaturePublicKey.toHex()}")}
            .signature

        return ProvenanceDIME.getDEK(dime.audienceList, encryptionKeyRef)
            .let { ProvenanceDIME.decryptPayload(this, it) }
            .verify(signaturePublicKey, signatureToUse)
    }

    fun length() = pos

    companion object {
        const val FIELDNAME = "DIME"
        private val OBJECT_MAPPER = ObjectMapper().configureProvenance()

        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
        // 0x44494D45L == DIME in ASCII
        const val MAGIC_BYTES = 0x44494D45L

        const val VERSION = 0x1

        fun parse(
            inputStream: InputStream,
            uri: String? = null,
            signatures: List<Signature>? = null,
            internalHash: Boolean = false,
            externalHash: Boolean = false
        ): DIMEInputStream {
            val magicBytes = ByteArray(4).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(ByteUtil::getUInt32)

            if (magicBytes != MAGIC_BYTES) {
                throw IllegalArgumentException("Provided input stream is not in DIME file format.")
            }
            val formatVersion = ByteArray(2).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(ByteUtil::getUInt16)

            if (formatVersion != VERSION) {
                throw IllegalArgumentException("Provided input stream is not in DIME file format uuid required: $VERSION")
            }

            val uuidLength = ByteArray(4).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(ByteUtil::getUInt32)

            val uuid = ByteArray(uuidLength.toInt()).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let {
                val bb = ByteBuffer.wrap(it)
                UUID(bb.getLong(0), bb.getLong(8))
            }

            val metadataLength = ByteArray(4).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(ByteUtil::getUInt32)

            val metadata = ByteArray(metadataLength.toInt()).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let { OBJECT_MAPPER.readValue<Map<String, String>>(it) }

            val uriLength = ByteArray(4).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(ByteUtil::getUInt32)

            val readUri = ByteArray(uriLength.toInt()).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.toString(charset = Charsets.UTF_8)

            val signaturesLength = ByteArray(4).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(ByteUtil::getUInt32)

            val readSignatures = ByteArray(signaturesLength.toInt())
                .apply {
                    readUntilFull(
                        inputStream,
                        this
                    )
            }.let {
                    if (it.isEmpty()) {
                        null
                    } else {
                        OBJECT_MAPPER.readValue<List<Signature>>(it)
                    }
            }

            val dimeProtoLength = ByteArray(4).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(ByteUtil::getUInt32)

            val dime = ByteArray(dimeProtoLength.toInt()).apply {
                readUntilFull(
                    inputStream,
                    this
                )
            }.let(DIME::parseFrom)

            return DIMEInputStream(
                dime,
                inputStream,
                metadata,
                uuid = uuid,
                uri = uri ?: readUri,
                signatures = signatures ?: readSignatures ?: listOf(),
                internalHash = internalHash,
                externalHash = externalHash
            )
        }

        fun readUntilFull(inputStream: InputStream, bytes: ByteArray) {
            if (bytes.isEmpty()) {
                return
            }
            var read = 0
            do {
                val res = inputStream.read(bytes, read, bytes.size - read)
                if (res < 0) {
                    throw EOFException("End of input stream reached.")
                }
                read += res
            } while (read < bytes.size)
        }
    }

    /**
     * @return ByteArray representing the SHA-512 of the input stream as read so far
     *          **NOTE** This might be the unencrypted SHA-512 if it is wrapping the initial HashingCipherInputStream,
     *          else it is the SHA-512 of the encrypted stream.
     */
    fun internalHash(): ByteArray {
        return when(internalInputStream) {
            is HashingCipherInputStream -> internalInputStream.hash()
            else -> internalDigest.digest()
        }
    }

    /**
     * @return ByteArray representing the SHA-512 of the entire [DIMEInputStream]
     *          including the header.
     */
    fun externalHash(): ByteArray {
        return externalDigest.digest()
    }

    override fun close() = `in`.close()
}
