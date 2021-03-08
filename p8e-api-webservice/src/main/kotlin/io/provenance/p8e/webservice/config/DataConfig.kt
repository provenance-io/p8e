package io.provenance.p8e.webservice.config

import io.provenance.p8e.shared.extension.logger
import io.provenance.p8e.shared.sql.HikariDataSourceBuilder
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.postgresql.Driver
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

@Configuration
@EnableConfigurationProperties(value = [DatabaseProperties::class])
class DataConfig {

    @Primary
    @Bean
    fun dataSource(databaseProperties: DatabaseProperties): DataSource {
        logger().info("Connecting to {}:{}/{}", databaseProperties.host, databaseProperties.port, databaseProperties.name)

        return HikariDataSourceBuilder()
            .jdbcType("jdbc:postgresql")
            .jdbcDriver(Driver::class.java.name)
            .hostname(databaseProperties.host)
            .port(databaseProperties.port.toInt())
            .name(databaseProperties.name)
            .username(databaseProperties.username)
            .password(databaseProperties.password)
            .schema(databaseProperties.schema)
            .connectionPoolSize(databaseProperties.connectionPoolSize.toInt())
            .properties(
                mutableMapOf(
                    "protocol.io.threads" to "12"
                )
            )
            .build()
    }
}

@Component
class DataMigration(dataSource: DataSource) {
    init {
        Database.connect(dataSource)
        Database.registerDialect("pgsql") { PostgreSQLDialect() }
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
    }
}
