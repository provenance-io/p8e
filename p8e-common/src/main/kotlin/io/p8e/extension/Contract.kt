package io.p8e.extension

import io.p8e.annotations.ScopeSpecification
import io.p8e.spec.P8eContract

fun <T : P8eContract> Class<T>.scopeSpecificationNames(): List<String> = annotations
    .filter { it is ScopeSpecification }
    .map { it as ScopeSpecification }
    .flatMap { it.names.toList() }
