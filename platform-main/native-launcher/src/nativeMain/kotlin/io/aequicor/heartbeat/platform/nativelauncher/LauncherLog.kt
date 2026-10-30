@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import platform.posix.fputs
import platform.posix.stderr

/** Native-реализация логирования лончера: stderr, чтобы не тянуть платформенные модули в native-таргеты. */
internal actual object LauncherLog {
    actual fun i(tag: String, message: () -> String) {
        write(tag, "INFO", message(), null)
    }

    actual fun w(tag: String, error: Throwable, message: () -> String) {
        write(tag, "WARN", message(), error)
    }

    private fun write(tag: String, level: String, message: String, error: Throwable?) {
        val suffix = error?.let { ": ${it::class.simpleName}: ${it.message}" } ?: ""
        fputs("[$tag] $level $message$suffix\n", stderr)
    }
}
