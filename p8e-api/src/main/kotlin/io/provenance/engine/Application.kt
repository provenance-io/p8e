package io.provenance.engine

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration
import org.springframework.context.annotation.ComponentScan

@ComponentScan(basePackages = ["io.provenance.engine", "io.provenance.p8e.shared"])
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, JooqAutoConfiguration::class])
class Application

fun main(args: Array<String>) {
    SpringApplication.run(Application::class.java, *args)
}
