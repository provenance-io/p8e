package io.p8e.classloader

import org.apache.commons.io.FileUtils
import java.io.*
import java.net.URI
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarEntry
import java.util.jar.JarFile


class MemoryClassLoader(
    hash: String,
    inputStream: InputStream,
    private val readEmbeddedJars: Boolean = true
): URLClassLoader(
    arrayOf()
) {
    private val parentClassLoader = MemoryClassLoader::class.java.classLoader
    private val system = ClassLoader.getSystemClassLoader()
    private val classCache = ConcurrentHashMap<String, Class<*>>()
    private val jarLoadedCache = ConcurrentHashMap<String, Boolean>()
        .also {
            it[hash] = true
        }

    init {
        addJarEntries(
            inputStream,
            readEmbeddedJars
        )
    }

    override fun getName(): String {
        return "MemoryClassLoader"
    }

    @Synchronized
    override fun loadClass(name: String): Class<*> {
        val alreadyLoaded = findLoadedClass(name)
        if (alreadyLoaded != null) {
            return alreadyLoaded
        }

        if (classCache.containsKey(name)) {
            return classCache[name]!!
        }

        var loadedFromParent: Boolean? = false
        val clazz = try {
            when {
                (name.startsWith("com.google.protobuf") ||
                        name.startsWith("io.p8e") ||
                        name.startsWith("io.p8e.proto.Util\$Index") ||
                        name.startsWith("io.provenance.proto.Util\$Index")) &&
                        !name.startsWith("io.p8e.proto.contract") &&
                        !name.startsWith("io.p8e.contracts") -> parentClassLoader.loadClass(name).also { loadedFromParent = true }
                else -> findClass(name).also { loadedFromParent = false }
            }
        } catch (t: Throwable) {
            when (t) {
                is ClassNotFoundException,
                is NoClassDefFoundError -> {
                    try {
                        when {
                            (name.startsWith("com.google.protobuf") ||
                                    name.startsWith("io.p8e") ||
                                    name.startsWith("io.p8e.proto.Util\$Index") ||
                                    name.startsWith("io.provenance.proto.Util\$Index")) &&
                                    !name.startsWith("io.p8e.proto.contract") &&
                                    !name.startsWith("io.p8e.contracts") -> findClass(name).also { loadedFromParent = false }
                            else -> parentClassLoader.loadClass(name).also { loadedFromParent = true }
                        }
                    } catch (t: Throwable) {
                        when (t) {
                            is ClassNotFoundException,
                            is NoClassDefFoundError -> system.loadClass(name).also { loadedFromParent = null }
                            else -> throw t
                        }
                    }
                }
                else -> throw t
            }
        }
        classCache[name] = clazz
        return clazz
    }

    fun addJar(
        hash: String,
        inputStream: InputStream
    ) {
        if (jarLoadedCache[hash] == true) {
            return
        }

        addJarEntries(
            inputStream,
            readEmbeddedJars
        )

        jarLoadedCache[hash] = true
    }

    private fun addJarEntries(
        inputStream: InputStream,
        readEmbeddedJars: Boolean
    ) {
        if (inputStream.available() == 0)
            return

        val rootJar = File.createTempFile("class-file", ".tmp").apply {
            FileUtils.writeByteArrayToFile(this, inputStream.readAllBytes())
        }.also {
            super.addURL(it.toURI().toURL())
        }.let {
            JarFile(it)
        }

        if (readEmbeddedJars) {
            rootJar
                .stream()
                .filter {
                    it.name.endsWith(".jar")
                }.map {
                    jarEntryAsUri(rootJar, it)
                }.forEach {
                    super.addURL(it!!.toURL())
                }
        }
    }

    private fun jarEntryAsUri(jarFile: JarFile?, jarEntry: JarEntry?): URI? {
        if (jarFile == null || jarEntry == null) throw IOException("Invalid jar file or entry")
        var input: InputStream? = null
        var output: OutputStream? = null
        return try {
            val name: String = jarEntry.getName().replace('/', '_')
            val i = name.lastIndexOf(".")
            val extension = if (i > -1) name.substring(i) else ""
            val file = File.createTempFile(
                name.substring(0, name.length - extension.length) +
                        ".", extension)
            file.deleteOnExit()
            input = jarFile.getInputStream(jarEntry)
            output = FileOutputStream(file)
            var readCount: Int
            val buffer = ByteArray(4096)
            while (input.read(buffer).also { readCount = it } != -1) {
                output.write(buffer, 0, readCount)
            }
            file.toURI()
        } finally {
            input?.close()
            output?.close()
        }
    }

    fun <T> forThread(fn: () -> T): T {
        val current = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = this
            return fn()
        } finally {
            Thread.currentThread().contextClassLoader = current
        }
    }
}
