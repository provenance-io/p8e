package io.provenance.p8e.webservice

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration
import org.springframework.context.annotation.ComponentScan

@ComponentScan(basePackages = ["io.provenance.p8e.webservice", "io.provenance.p8e.shared"])
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, JooqAutoConfiguration::class])
class Application

fun main(args: Array<String>) {
    SpringApplication.run(Application::class.java, *args)
}
