package io.aequicor.heartbeat.core.logging

import io.github.aakira.napier.Antilog
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.logging.ConsoleHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import io.github.aakira.napier.LogLevel as NapierLevel

internal actual fun platformDebugAntilog(): Antilog = JvmConsoleAntilog()

/** Owns its handler directly: reinitializing Napier must not accumulate handlers on a shared JUL logger. */
private class JvmConsoleAntilog : Antilog() {
    private val handler = ConsoleHandler().apply {
        // Gradle/IDE consoles expect UTF-8, even when the Windows JVM uses a legacy charset.
        encoding = Charsets.UTF_8.name()
        level = Level.ALL
        formatter = CompactLogFormatter()
    }

    override fun performLog(priority: NapierLevel, tag: String?, throwable: Throwable?, message: String?) {
        val label = when (priority) {
            NapierLevel.WARNING -> "WARN"
            NapierLevel.ASSERT -> "ERROR"
            NapierLevel.VERBOSE, NapierLevel.DEBUG, NapierLevel.INFO, NapierLevel.ERROR -> priority.name
        }
        val record = LogRecord(priority.toJul(), "[$label] ${tag.orEmpty()} - ${message.orEmpty()}").apply {
            thrown = throwable
        }
        handler.publish(record)
    }
}

private class CompactLogFormatter : Formatter() {
    private val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    override fun format(record: LogRecord): String = buildString {
        append(timestamp.format(record.instant))
        append(' ')
        append(record.message.replace("\r", "\\r").replace("\n", "\\n"))
        append('\n')
        record.thrown?.let {
            append(it.stackTraceToString())
            append('\n')
        }
    }
}

private fun NapierLevel.toJul(): Level = when (this) {
    NapierLevel.VERBOSE -> Level.FINEST
    NapierLevel.DEBUG -> Level.FINE
    NapierLevel.INFO -> Level.INFO
    NapierLevel.WARNING -> Level.WARNING
    NapierLevel.ERROR, NapierLevel.ASSERT -> Level.SEVERE
}
