package io.provenance.pbc.clients

import com.fasterxml.jackson.core.JsonGenerator.Feature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

fun ObjectMapper.configureCanonical(): ObjectMapper =
        registerKotlinModule()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // This is NOT compliant with RFC7159 but it is required for Cosmos
                .enable(Feature.WRITE_NUMBERS_AS_STRINGS)
                .disable(SerializationFeature.INDENT_OUTPUT)
