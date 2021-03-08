package io.p8e.util

import com.google.protobuf.Message
import java.io.ByteArrayInputStream

class ProtoInputStream<T: Message>(message: T): ByteArrayInputStream(message.toByteArray()) {
    val length = super.count
}