package io.provenance.p8e.webservice.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import io.p8e.util.configureProvenance

private val om = ObjectMapper().configureProvenance()
private val oWriter = om.writer()
private val oReader = om.reader()

fun Any.toJsonNode(ow: ObjectWriter = oWriter, or: ObjectReader = oReader) =
    or.readTree(ow.writeValueAsBytes(this))

class AccessDeniedException(message: String) : RuntimeException(message)
