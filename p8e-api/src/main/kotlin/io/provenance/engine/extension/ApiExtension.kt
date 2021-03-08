package io.provenance.engine.extension

import io.p8e.proto.Common.ProvenanceReference

/**
 * Convert/wrap a string (hash) to a ProvenanceReference proto.
 */
fun String.toProvRef(): ProvenanceReference = ProvenanceReference.newBuilder().setHash(this).build()