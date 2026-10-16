package io.aequicor.heartbeat.core.logging

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopLogDirectoryTest {
    @Test
    fun `macOS and Windows preserve existing log locations`() {
        assertEquals(
            Path.of("/home", "Library", "Logs", "Heartbeat"),
            desktopLogDirectory("Mac OS X", "/home", null, null),
        )
        assertEquals(
            Path.of("/local", "Aequicor", "Heartbeat", "logs"),
            desktopLogDirectory("Windows 11", "/home", "/local", null),
        )
        assertEquals(
            Path.of("/home", "AppData", "Local", "Aequicor", "Heartbeat", "logs"),
            desktopLogDirectory("Windows 11", "/home", null, null),
        )
        assertEquals(emptyList(), desktopLegacyLogDirectories("Mac OS X", "/home", null))
        assertEquals(emptyList(), desktopLegacyLogDirectories("Windows 11", "/home", null))
    }

    @Test
    fun `Linux uses absolute XDG state and keeps prior logs discoverable`() {
        val state = Path.of("state").toAbsolutePath()
        assertEquals(
            state.resolve("heartbeat").resolve("logs"),
            desktopLogDirectory("Linux", "/home", null, state.toString()),
        )
        assertEquals(
            Path.of("/home", ".local", "state", "heartbeat", "logs"),
            desktopLogDirectory("Linux", "/home", null, "relative-state"),
        )
        assertEquals(
            listOf(Path.of("/home", "AppData", "Local", "Aequicor", "Heartbeat", "logs")),
            desktopLegacyLogDirectories("Linux", "/home", null),
        )
    }
}
