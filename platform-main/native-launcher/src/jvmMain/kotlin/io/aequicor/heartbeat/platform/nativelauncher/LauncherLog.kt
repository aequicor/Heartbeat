package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.core.logging.Log

/** JVM-реализация логирования лончера через core:logging. */
internal actual object LauncherLog {
    actual fun i(tag: String, message: () -> String) {
        Log.tag(tag).i(message)
    }

    actual fun w(tag: String, error: Throwable, message: () -> String) {
        Log.tag(tag).w(error, message)
    }
}
