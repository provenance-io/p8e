package io.provenance.p8e.shared.extension

import ch.qos.logback.classic.Level
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.jvmName

fun logger(name: String = pkgName()): Logger = LoggerFactory.getLogger(name)
inline fun <reified T : Any> logger(clazz: KClass<T>): Logger = logger(clazz.jvmName)
inline fun <reified T : Any> T.logger(): Logger = logger(T::class)
fun pkgName(): String = object {}::class.java.`package`.name

var Logger.level by object {
    operator fun getValue(thisRef: Logger, property: KProperty<*>): Level {
        if (thisRef is ch.qos.logback.classic.Logger) {
            return thisRef.level
        }
        throw RuntimeException("Invalid reference type ${thisRef.javaClass}")
    }

    operator fun setValue(thisRef: Logger, property: KProperty<*>, value: Level) {
        if (thisRef is ch.qos.logback.classic.Logger) {
            thisRef.level = value
            return
        }
        throw RuntimeException("Invalid reference type ${thisRef.javaClass}")
    }
}
