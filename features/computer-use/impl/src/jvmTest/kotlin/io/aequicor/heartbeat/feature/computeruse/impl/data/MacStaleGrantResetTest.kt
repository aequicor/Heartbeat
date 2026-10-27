package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacStaleGrantResetTest {
    @Test
    fun `launcher inside an app bundle is an installed bundle`() {
        val bundle = MacStaleGrantReset.installedBundle("/Applications/Heartbeat.app/Contents/MacOS/Heartbeat")
        assertEquals("/Applications/Heartbeat.app", bundle?.path)
    }

    @Test
    fun `java of an IDE bundle is not an installed bundle`() {
        assertNull(MacStaleGrantReset.installedBundle("/Applications/IDEA.app/Contents/jbr/Contents/Home/bin/java"))
        assertNull(MacStaleGrantReset.installedBundle("/usr/bin/java"))
        assertNull(MacStaleGrantReset.installedBundle(null))
    }

    @Test
    fun `bundle identifier is read from an XML plist`() {
        val plist = "<dict><key>CFBundleName</key><string>Heartbeat</string>" +
            "<key>CFBundleIdentifier</key>\n  <string>io.aequicor.heartbeat.platform.desktop</string></dict>"
        assertEquals("io.aequicor.heartbeat.platform.desktop", MacStaleGrantReset.bundleId(plist))
        assertNull(MacStaleGrantReset.bundleId("bplist00"))
    }

    @Test
    fun `reset command names the service and the bundle identifier`() {
        val root = Files.createTempDirectory("stale-grant").toFile()
        try {
            val contents = root.resolve("Heartbeat.app/Contents").apply { resolve("MacOS").mkdirs() }
            contents.resolve("Info.plist")
                .writeText("<key>CFBundleIdentifier</key><string>io.example.app</string>")
            val executable = contents.resolve("MacOS/Heartbeat").path
            assertEquals(
                listOf("tccutil", "reset", "Accessibility", "io.example.app"),
                MacStaleGrantReset.command(ComputerUsePermission.Accessibility, executable),
            )
            assertEquals(
                listOf("tccutil", "reset", "ScreenCapture", "io.example.app"),
                MacStaleGrantReset.command(ComputerUsePermission.ScreenRecording, executable),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
