package io.p8e.extension

import java.io.OutputStream
import java.io.PrintStream
import java.util.*

fun <T> withoutLogging(block: () -> T): T =
    ThreadPausableSystemOut.pause().let {
        try {
            block()
        } finally {
            ThreadPausableSystemOut.play()
        }
    }

class NoOpOutputStream : OutputStream() {
    override fun write(b: Int) {

    }
}

object ThreadPausableSystemOut : PrintStream(NoOpOutputStream()) {
    private val standardOut = System.out
    private val isActive = ThreadLocal.withInitial { true }

    init {
        System.setOut(this)
    }

    fun pause() = isActive.set(false)
    fun play() = isActive.set(true)

    private fun ifActive(block: () -> Unit): Unit {
        if (isActive.get()) {
            block()
        }
    }

    override fun print(b: Boolean) = ifActive { standardOut.print(b) }
    override fun print(c: Char) = ifActive { standardOut.print(c) }
    override fun print(i: Int) = ifActive { standardOut.print(i) }
    override fun print(l: Long) = ifActive { standardOut.print(l) }
    override fun print(f: Float) = ifActive { standardOut.print(f) }
    override fun print(d: Double) = ifActive { standardOut.print(d) }
    override fun print(s: CharArray) = ifActive { standardOut.print(s) }
    override fun print(s: String) = ifActive { standardOut.print(s) }
    override fun print(obj: Any?) = ifActive { standardOut.print(obj) }
    override fun println() = ifActive { standardOut.println() }
    override fun println(x: Boolean) = ifActive { standardOut.println(x) }
    override fun println(x: Char) = ifActive { standardOut.println(x) }
    override fun println(x: Int) = ifActive { standardOut.println(x) }
    override fun println(x: Long) = ifActive { standardOut.println(x) }
    override fun println(x: Float) = ifActive { standardOut.println(x) }
    override fun println(x: Double) = ifActive { standardOut.println(x) }
    override fun println(x: CharArray) = ifActive { standardOut.println(x) }
    override fun println(x: String) = ifActive { standardOut.println(x) }
    override fun println(x: Any?) = ifActive { standardOut.println(x) }

    override fun printf(format: String?, vararg args: Any?): PrintStream? = apply {
        ifActive { standardOut.format(format, *args) }
    }

    override fun printf(l: Locale?, format: String?, vararg args: Any?): PrintStream? = apply {
        ifActive { standardOut.printf(l, format, *args) }
    }

    override fun format(format: String?, vararg args: Any?): PrintStream? = apply {
        ifActive { standardOut.format(format, *args) }
    }

    override fun format(l: Locale, format: String?, vararg args: Any?): PrintStream? = apply {
        ifActive { standardOut.format(l, format, *args) }
    }

    override fun append(csq: CharSequence): PrintStream? = apply {
        ifActive { standardOut.append(csq) }
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): PrintStream? = apply {
        ifActive { standardOut.append(csq, start, end) }
    }

    override fun append(c: Char): PrintStream? = apply {
        ifActive { standardOut.append(c) }
    }

    override fun write(buf: ByteArray?, off: Int, len: Int) = ifActive { standardOut.write(buf, off, len) }

    override fun write(b: Int) = ifActive { standardOut.write(b) }

    override fun flush() {
        standardOut.flush()
    }

    override fun close() {
        super.close()
        standardOut.close()
    }

    override fun checkError(): Boolean {
        return standardOut.checkError()
    }
}
