package io.p8e.proxy

import com.google.protobuf.Message
import io.p8e.ContractManager
import io.p8e.proto.Contracts.Contract
import io.p8e.util.ThreadPoolFactory
import io.p8e.util.base64Decode
import io.p8e.util.loBytes
import io.p8e.util.sha256
import io.p8e.util.sha512
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlin.concurrent.thread

class PermissionUpdater(
    private val contractManager: ContractManager,
    private val contract: Contract,
    private val audience: Set<PublicKey>
) {

    fun saveConstructorArguments() {
        // TODO this can be optimized by checking the recitals and record groups and determining what subset, if any,
        // of input facts need to be fetched and stored in order only save the objects that are needed by some of
        // the recitals
        contract.inputsList.threadedMap(executor) { fact ->
            with (contractManager.client) {
                val obj = this.loadObject(fact.dataLocation.ref.hash)
                val inputStream = ByteArrayInputStream(obj)

                val hashBytes = fact.dataLocation.ref.hash.base64Decode()
                val loHash = hashBytes.size == 16
                val msgSha256 = obj.sha256()
                val useSha256 = if (loHash) {
                    msgSha256.loBytes().toByteArray().contentEquals(hashBytes)
                } else {
                    msgSha256.contentEquals(hashBytes)
                }

                this.storeObject(inputStream, audience, sha256 = useSha256, loHash = loHash)
            }
        }
    }

    fun saveProposedFacts(stagedExecutionUuid: UUID, stagedProposedProtos: Collection<Message>) {
        stagedProposedProtos.threadedMap(executor) {
            contractManager.saveProto(it, stagedExecutionUuid, audience)
        }
    }

companion object {
        // TODO this can be optimized on a single thread by using an async grpc stub and accumulating the results
        // via a stream observer. We never did this yet, but this could even be marked as endpoints we can hit right
        // on the raw netty network threads since it would be nonblocking...I'm not sure if you can configure that
        // for a specific endpoint though, or the whole managed channel.
        private val executor = ThreadPoolFactory.newFixedDaemonThreadPool(16, "permission-updater-%d")
    }
}

fun<T> Collection<T>.threadedMap(executor: ExecutorService, fn: (T) -> Any?): Void? =
    this.map { item ->
        CompletableFuture<Any?>().also { future ->
            thread(start = false) {
                try {
                    fn(item)
                    future.complete(null)
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }.let(executor::submit)
        }
    }.let { CompletableFuture.allOf(*it.toTypedArray()) }
        .get()
