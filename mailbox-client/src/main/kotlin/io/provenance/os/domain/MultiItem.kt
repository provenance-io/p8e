package io.provenance.os.domain

import java.util.UUID

data class MultiItem(
    val count: Int,
    val boundary: String,
    val sizes: List<Long>,
    val uuids: List<UUID>,
    val sha512s: List<ByteArray>,
    val signatures: List<List<Signature>>
)