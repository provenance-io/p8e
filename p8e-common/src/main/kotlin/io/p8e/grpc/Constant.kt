package io.p8e.grpc

import com.google.protobuf.Empty
import com.google.protobuf.Message
import io.grpc.Context
import io.grpc.Metadata
import io.grpc.stub.StreamObserver
import io.p8e.util.toJavaPublicKey

object Constant {
    val JWT_ALGORITHM = "SHA256withECDSA"
    val PUBLIC_KEY_CTX = Context.key<String>("public-key")
    val CLIENT_IP_CTX = Context.key<String>("client-ip")
    val CLIENT_VERSION_CTX = Context.key<String>("client-version")
    val CLIENT_VERSION_KEY = Metadata.Key.of("p8e-client-version", Metadata.ASCII_STRING_MARSHALLER)
    val JWT_METADATA_KEY = Metadata.Key.of("jwt", Metadata.ASCII_STRING_MARSHALLER)
    val JWT_CTX_KEY = Context.key<String>("jwt")
    val MAX_MESSAGE_SIZE = 200 * 1024 * 1024
}

fun publicKey() = Constant.PUBLIC_KEY_CTX.get().toJavaPublicKey()
fun clientIp() = Constant.CLIENT_IP_CTX.get()
fun clientVersion() = Constant.CLIENT_VERSION_CTX.get()

fun <T: Message> T.complete(observer: StreamObserver<T>) {
    observer.onNext(this)
    observer.onCompleted()
}

fun StreamObserver<Empty>.complete() {
    onNext(Empty.getDefaultInstance())
    onCompleted()
}
