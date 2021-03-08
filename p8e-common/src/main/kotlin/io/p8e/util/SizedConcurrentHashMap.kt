package io.p8e.util

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

// TODO fix this to a guava hash map or something so we can remove least used keys or time based
// expiration? This max size could be the source of our occasional OOMs
class SizedConcurrentHashMap<K, V>(private val maxSize: Int = MAX_CACHE_SIZE): ConcurrentHashMap<K, V>() {
    companion object {
        private const val MAX_CACHE_SIZE = 10000
    }

    override fun computeIfAbsent(key: K, mappingFunction: Function<in K, out V>): V {
        if (size > maxSize)
            clear()

        return super.computeIfAbsent(key, mappingFunction)
    }

    override fun put(key: K, value: V): V? {
        if (size > maxSize)
            clear()

        return super.put(key, value)
    }
}
