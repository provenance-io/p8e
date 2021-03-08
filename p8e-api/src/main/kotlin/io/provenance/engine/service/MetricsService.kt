package io.provenance.engine.service

import com.timgroup.statsd.StatsDClient
import io.p8e.util.registerTimeInterceptor
import io.provenance.p8e.shared.extension.logger
import io.provenance.engine.batch.shutdownHook
import java.time.Duration

interface MetricCollector {
    fun time(aspect: String, value: Duration, labels: Map<String, String> = mapOf())
}

class MetricsService(private val collectors: List<MetricCollector>, private val globalTags: Map<String, String>): MetricCollector {
    init {
        registerTimeInterceptor { aspect, start, end -> time(aspect, Duration.between(start, end), mapOf("function" to "timed")) }
    }
    
    override fun time(aspect: String, value: Duration, labels: Map<String, String>) = collectors.forEach { it.time(aspect, value, globalTags + labels) }
}

class DataDogMetricCollector(private val dataDogClient: StatsDClient) : MetricCollector {
    init {
        shutdownHook { dataDogClient.close() }
    }

    override fun time(aspect: String, value: Duration, labels: Map<String, String>) {
        dataDogClient.time(aspect, value.toMillis(), *labels.map { (key, value) -> "$key:$value" }.toTypedArray())
    }
}

class LogFileMetricCollector(prefix: String) : MetricCollector {
    private val metricRegex = "[a-zA-Z_:][a-zA-Z0-9_:]*".toRegex() // prometheus-style metric naming regex
    private val labelRegex = "[a-zA-Z_][a-zA-Z0-9_]*".toRegex()
    private val log = logger()
    private val _prefix = prefix.takeIf { it.isNotBlank() }?.let { "${it}_" } ?: ""

    init {
        if (!prefix.matches(metricRegex)) {
            log.warn("Prefix $prefix is invalid, must match the regex $metricRegex")
        }
    }

    override fun time(aspect: String, value: Duration, labels: Map<String, String>) {
        if (!aspect.matches(metricRegex)) {
            log.warn("aspect $aspect is invalid, should match the regex $metricRegex")
        }

        labels.forEach { (key, _) ->
            if (!key.matches(labelRegex)) {
                log.warn("label $key is invalid, should match the regex $labelRegex")
            }
        }

        val labelString = labels
            .takeIf { it.isNotEmpty() }
            ?.toList()
            ?.joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=\"$value\"" }
            ?: ""
        log.info("$_prefix${aspect}_seconds$labelString ${value.toMillis() / 1000.0}")
    }
}
