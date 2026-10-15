package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionArchiveTest {
    private val root = Files.createTempDirectory("heartbeat-extension-archive")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `absolute traversal Windows and ambiguous paths are rejected`() = runTest {
        listOf(
            "../outside",
            "/absolute",
            "C:/drive",
            "folder\\file",
            "folder/../file",
            "./file",
            "folder//file",
        ).forEach { name ->
            val target = Files.createTempDirectory(root, "extract-")
            val archive = Files.createTempFile(root, "raw-name-", ".zip")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("data".toByteArray())
                zip.closeEntry()
            }
            assertFailsWith<IllegalArgumentException>(name) { ExtensionArchive().extract(archive, target) }
        }
        assertFalse(Files.exists(root.resolve("outside")))
    }

    @Test
    fun `links special files duplicate names and reserved marker are rejected`() = runTest {
        val cases = listOf(
            listOf("link" to 0xA1FF),
            listOf("pipe" to 0x11FF),
            listOf("duplicate" to 0, "duplicate" to 0),
            listOf(COMPLETION to 0),
        )
        for (entries in cases) {
            val target = Files.createTempDirectory(root, "extract-")
            val archive = archive(entries)
            assertFailsWith<IllegalArgumentException> { ExtensionArchive().extract(archive, target) }
        }
    }

    @Test
    fun `expanded byte and entry counts are bounded`() = runTest {
        val archive = archive(listOf("one" to 0, "two" to 0))
        assertFailsWith<IllegalArgumentException> {
            ExtensionArchive(maxBytes = 1).extract(archive, Files.createTempDirectory(root, "bytes-"))
        }
        assertFailsWith<IllegalArgumentException> {
            ExtensionArchive(maxEntries = 1).extract(archive, Files.createTempDirectory(root, "entries-"))
        }
    }

    @Test
    fun `only the pinned publisher tag and bounded size are accepted`() {
        val pin = "version=1.2.3\nsha256=${"a".repeat(64)}\nsize=12\n"
        val prefix = "https://github.com/aequicor/Heartbeat/releases/download/"
        val valid = ExtensionRelease.read(pin + "url=${prefix}v1.2.3/extension.zip")
        assertTrue(valid.artifact.download.allowedHosts.contains("github.com"))
        listOf(
            "http://github.com/aequicor/Heartbeat/releases/download/v1.2.3/extension.zip",
            "${prefix}v9.0.0/extension.zip",
            "${prefix}v1.2.3/extension.zip?redirect=bad",
            "${prefix}v1.2.3/subfolder/extension.zip",
            "https://attacker.test/extension.zip",
        ).forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { ExtensionRelease.read(pin + "url=$url") }
        }
    }

    private fun archive(entries: List<Pair<String, Int>>): java.nio.file.Path {
        val archive = Files.createTempFile(root, "component-", ".zip")
        ZipArchiveOutputStream(archive).use { output ->
            for ((name, mode) in entries) {
                val entry = ZipArchiveEntry(name)
                entry.unixMode = mode
                output.putArchiveEntry(entry)
                output.write("data".toByteArray())
                output.closeArchiveEntry()
            }
        }
        return archive
    }
}
