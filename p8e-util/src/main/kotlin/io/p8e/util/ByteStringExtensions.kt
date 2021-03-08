package io.p8e.util

import com.google.protobuf.ByteString

fun String.toByteString() = toByteArray().toByteString()
fun ByteArray.toByteString() = ByteString.copyFrom(this)
