package io.p8e.util

import com.google.common.io.BaseEncoding
import com.google.protobuf.ByteString

fun String.base64encode() = BaseEncoding.base64().encode(this.toByteArray())
fun String.base64decode() = BaseEncoding.base64().decode(this)
fun ByteArray.base64encodeBytes() = BaseEncoding.base64().encode(this).toByteArray()
fun ByteArray.base64decodeBytes() = BaseEncoding.base64().decode(asString())
fun ByteString.base64encodeBytes() = BaseEncoding.base64().encode(this.toByteArray())
fun ByteString.base64decodeBytes() = BaseEncoding.base64().decode(toStringUtf8())
