package io.aequicor.heartbeat.core.logging

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.DebugAntilog
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.SimpleFormatter

internal actual fun platformDebugAntilog(): Antilog = DebugAntilog(
    handler = listOf(
        ConsoleHandler().apply {
            // Gradle/IDE consoles expect UTF-8, even when the Windows JVM uses a legacy charset.
            encoding = Charsets.UTF_8.name()
            level = Level.ALL
            formatter = SimpleFormatter()
        },
    ),
)
