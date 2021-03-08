package io.p8e.classloader

import java.util.concurrent.ConcurrentHashMap

object ClassLoaderCache {
    val classLoaderCache = ConcurrentHashMap<String, MemoryClassLoader>()
}