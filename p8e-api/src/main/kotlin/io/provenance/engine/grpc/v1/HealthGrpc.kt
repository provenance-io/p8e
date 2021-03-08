package io.provenance.engine.grpc.v1

import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.health.v1.HealthCheckResponse.ServingStatus.NOT_SERVING
import io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING
import io.grpc.health.v1.HealthGrpc.HealthImplBase
import io.grpc.stub.StreamObserver
import io.p8e.grpc.complete
import io.provenance.p8e.shared.extension.logger
import org.lognet.springboot.grpc.GRpcService
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

@GRpcService
class HealthGrpc(
    private val dataSource: DataSource
): HealthImplBase() {
    override fun check(
        request: HealthCheckRequest,
        responseObserver: StreamObserver<HealthCheckResponse>
    ) {
        HealthCheckResponse.newBuilder()
            .setStatus(if (dbCheck()) { SERVING } else { NOT_SERVING })
            .build()
            .complete(responseObserver)
    }

    private fun dbCheck(): Boolean {
        return try {
            dataSource.connection
                .use {
                    it.unwrap(Connection::class.java)
                        .let { connection ->
                            connection.createStatement()
                                .use { statement ->
                                    statement.execute("SELECT 1;")
                                }
                        }
                    true
                }
        } catch (t: SQLException) {
            logger().error("Error connecting to database.")
            false
        }
    }
}
