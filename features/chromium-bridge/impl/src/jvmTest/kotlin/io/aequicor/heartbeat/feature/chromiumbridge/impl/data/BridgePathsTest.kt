package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BridgePathsTest {
    @Test
    fun `short base keeps the socket in its run directory`() {
        val base = Path.of("/tmp/hb")

        assertEquals(Path.of("/tmp/hb/run/bridge.sock"), BridgePaths.socket(base))
    }

    @Test
    fun `too long base falls back to a stable per user temporary directory`() {
        val base = Path.of("/tmp/" + "a".repeat(120))

        val socket = BridgePaths.socket(base)

        assertTrue(socket.toString().length <= 100 || socket.startsWith(System.getProperty("java.io.tmpdir")))
        assertTrue(socket.parent.fileName.toString().startsWith("heartbeat-"))
        assertEquals(socket, BridgePaths.socket(base))
    }

    @Test
    fun `private directory is readable only by the owner`() {
        val root = Files.createTempDirectory("hb-paths")
        try {
            val directory = root.resolve("run")

            BridgePaths.ensurePrivateDirectory(directory)

            assertTrue(Files.isDirectory(directory))
            if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
