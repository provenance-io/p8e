package io.provenance.engine.config

import io.provenance.p8e.shared.extension.logger
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar

@EnableScheduling
@Configuration
@EnableConfigurationProperties(value = [ReaperProperties::class])
class SchedulingConfig(private val reaperProperties: ReaperProperties) : SchedulingConfigurer {

    private val log = logger()
    private val taskScheduler = ThreadPoolTaskScheduler()
        .also {
            it.poolSize = reaperProperties.schedulerPoolSize.toInt()
            it.setErrorHandler { t -> log.error("Exception in reaper task {}:{}. ", t.javaClass.name, t.stackTrace.firstOrNull(), t) }
            it.threadNamePrefix = "reaper-"

            it.initialize()
        }

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        log.info("Registering task {}", taskRegistrar)
        taskRegistrar.setScheduler(taskScheduler)
    }
}
