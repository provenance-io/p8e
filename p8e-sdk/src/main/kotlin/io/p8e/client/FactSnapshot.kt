package io.p8e.client

import com.google.protobuf.Message
import io.p8e.proto.Contracts.Recital
import io.p8e.proto.PK
import java.time.OffsetDateTime

data class FactSnapshot<T: Message>(
    val executor: PK.SigningAndEncryptionPublicKeys,
    val parties: List<Recital>,
    val contractJarHash: String,
    val contractClassname: String,
    val functionName: String,
    val resultName: String,
    val resultHash: String,
    val fact: T,
    val updated: OffsetDateTime,
    val blockNumber: Long,
    val blockTransactionIndex: Long
)