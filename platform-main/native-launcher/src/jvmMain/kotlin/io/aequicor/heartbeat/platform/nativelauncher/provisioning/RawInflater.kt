package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import java.util.zip.Inflater

/** JVM-реализация raw-deflate распаковки на java.util.zip.Inflater. */
@Suppress("UnusedPrivateProperty") // detekt не видит использования свойств в actual-функциях KMP.
internal actual class RawInflater actual constructor() {
    private val inflater = Inflater(true)

    actual fun input(data: ByteArray) {
        inflater.setInput(data)
    }

    actual fun inflate(output: ByteArray): Int = inflater.inflate(output)

    actual fun needsInput(): Boolean = inflater.needsInput()

    actual fun finished(): Boolean = inflater.finished() || inflater.needsDictionary()

    actual fun end() {
        inflater.end()
    }
}
