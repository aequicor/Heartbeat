package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolBridgeEndpoint
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class ClaudeMcpDirectoryTest {
    private val root = Files.createTempDirectory("claude-mcp-directory")
    private val sharedTemp = root.resolve("shared-temp")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun `Linux users with separate homes never share MCP files`() {
        val first = directory(root.resolve("first"))
        val second = directory(root.resolve("second"))
        Files.writeString(first.resolve("instructions.txt"), "First user's instructions")

        assertNotEquals(first, second)
        assertEquals(root.resolve("first/.cache/heartbeat/mcp"), first)
        assertEquals(root.resolve("second/.cache/heartbeat/mcp"), second)
        assertFalse(Files.exists(second.resolve("instructions.txt")))
        assertFalse(Files.exists(sharedTemp))
    }

    @Test
    fun `Linux honors separate absolute XDG cache directories`() {
        val first = directory(root.resolve("first"), root.resolve("cache-first").toString())
        val second = directory(root.resolve("second"), root.resolve("cache-second").toString())

        assertEquals(root.resolve("cache-first/heartbeat/mcp"), first)
        assertEquals(root.resolve("cache-second/heartbeat/mcp"), second)
        assertNotEquals(first, second)
        assertTrue(Files.isDirectory(first))
        assertTrue(Files.isDirectory(second))
    }

    @Test
    fun `Linux ignores an absent empty or relative XDG cache path`() {
        val home = root.resolve("user")
        for (cache in listOf(null, "", "relative-cache")) {
            assertEquals(home.resolve(".cache/heartbeat/mcp"), directory(home, cache))
        }
    }

    @Test
    fun `an unusable legacy shared temporary path does not block Linux instructions`() {
        Files.createDirectories(sharedTemp)
        val hostilePath = Files.writeString(sharedTemp.resolve("heartbeat-mcp"), "Foreign owner's file")

        val directory = directory(root.resolve("user"))
        val instructions = Files.writeString(directory.resolve("instructions.txt"), "Private instructions")

        assertTrue(Files.isRegularFile(instructions))
        assertEquals("Foreign owner's file", Files.readString(hostilePath))
    }

    @Test
    fun `the directory and MCP configuration remain owner only`() {
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView::class.java) != null)
        val directory = directory(root.resolve("user"))
        val config = claudeHostedConfig(AgentToolBridgeEndpoint("http://127.0.0.1:1", "test-token"), null, directory)

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(config))
    }

    @Test
    fun `preparing a user directory removes old files but retains current operations`() {
        val home = root.resolve("user")
        val directory = directory(home)
        val stale = Files.writeString(directory.resolve("stale.json"), "{}")
        val active = Files.writeString(directory.resolve("active.json"), "{}")
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 48L * 60 * 60 * 1000))

        assertEquals(directory, directory(home))
        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(active))
    }

    @Test
    fun `other desktop hosts retain their user temporary directory`() {
        for (system in listOf("Mac OS X", "Windows 11")) {
            val directory = claudeMcpDirectory(
                system,
                root.resolve("user"),
                root.resolve("cache").toString(),
                sharedTemp,
            )
            assertEquals(sharedTemp.resolve("heartbeat-mcp"), directory)
        }
    }

    private fun directory(home: Path, cache: String? = null): Path =
        claudeMcpDirectory("Linux", home, cache, sharedTemp)
}
