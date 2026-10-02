package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveExtractorTest {
    private val workspace: Path = Files.createTempDirectory("heartbeat-extract")
    private val root: Path = workspace.resolve("root").createDirectories()
    private val isPosix = "posix" in workspace.fileSystem.supportedFileAttributeViews()

    @AfterTest
    fun cleanUp() {
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `a tar archive unpacks below the root with stripped components and executable bits`() {
        val archive = archive(
            tarGz(
                directory("pi/"),
                file("pi/pi", "#!/bin/sh", mode = 0b111_101_101),
                directory("pi/docs/"),
                file("pi/docs/README.md", "Pi"),
            ),
        )

        ArchiveExtractor().extract(archive, ArchiveKind.TarGz(stripComponents = 1), root)

        assertEquals("#!/bin/sh", root.resolve("pi").readText())
        assertEquals("Pi", root.resolve("docs/README.md").readText())
        assertTrue(root.resolve("docs").isDirectory())
        if (isPosix) {
            assertTrue(root.resolve("pi").isExecutable())
            assertFalse(root.resolve("docs/README.md").isExecutable())
        }
    }

    @Test
    fun `entries that would leave the root are refused`() {
        listOf("../evil", "/etc/evil", "a/../../evil", "C:evil", "a\\..\\evil", "bad\u0001name").forEach { name ->
            val target = workspace.resolve("case-${name.hashCode()}").createDirectories()
            assertInstallFailure(InstallFailureReason.InvalidArchive) {
                ArchiveExtractor().extract(archive(tarGz(file(name, "x"))), ArchiveKind.TarGz(), target)
            }
        }
        assertFalse(workspace.resolve("evil").exists())
    }

    @Test
    fun `links and special files are refused`() {
        listOf('1', '2', '3', '6').forEach { type ->
            val target = workspace.resolve("special-$type").createDirectories()
            assertInstallFailure(InstallFailureReason.InvalidArchive) {
                ArchiveExtractor().extract(
                    archive(tarGz(TarItem("bin/link", type = type, link = "/etc/passwd"))),
                    ArchiveKind.TarGz(),
                    target,
                )
            }
        }
    }

    @Test
    fun `long names from pax and GNU headers are honoured`() {
        val deep = "release/" + "segment/".repeat(20) + "tool"
        val archive = archive(
            tarGz(paxPath(deep), file("short", "pax"), gnuLongName("$deep-gnu"), file("other", "gnu")),
        )

        ArchiveExtractor().extract(archive, ArchiveKind.TarGz(stripComponents = 1), root)

        val prefix = "segment/".repeat(20)
        assertEquals("pax", root.resolve("${prefix}tool").readText())
        assertEquals("gnu", root.resolve("${prefix}tool-gnu").readText())
        assertFalse(root.resolve("short").exists())
    }

    @Test
    fun `a damaged header is refused`() {
        val tar = tarGz(file("tool", "x"))
        val raw = java.util.zip.GZIPInputStream(tar.inputStream()).readBytes()
        raw[0] = 'X'.code.toByte()
        val damaged = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(raw) }
        }.toByteArray()

        assertInstallFailure(InstallFailureReason.InvalidArchive) {
            ArchiveExtractor().extract(archive(damaged), ArchiveKind.TarGz(), root)
        }
    }

    @Test
    fun `extraction limits bound the entry count and the unpacked size`() {
        val two = archive(tarGz(file("a", "1"), file("b", "2")))
        assertInstallFailure(InstallFailureReason.InvalidArchive) {
            ArchiveExtractor(ExtractionLimits(maxEntries = 1)).extract(two, ArchiveKind.TarGz(), root)
        }
        val large = archive(zip("big.bin" to "x".repeat(10_000)), "release.zip")
        val target = workspace.resolve("large").createDirectories()
        assertInstallFailure(InstallFailureReason.InvalidArchive) {
            ArchiveExtractor(ExtractionLimits(maxTotalBytes = 1_000)).extract(large, ArchiveKind.Zip(), target)
        }
    }

    @Test
    fun `a zip archive unpacks with stripped components and refuses traversal`() {
        val archive = archive(zip("codex/" to "", "codex/bin/codex.exe" to "MZ", "codex/LICENSE" to "MIT"), "codex.zip")

        ArchiveExtractor().extract(archive, ArchiveKind.Zip(stripComponents = 1), root)

        assertEquals("MZ", root.resolve("bin/codex.exe").readText())
        assertEquals("MIT", root.resolve("LICENSE").readText())
        val evil = archive(zip("../evil.exe" to "MZ"), "evil.zip")
        val target = workspace.resolve("zip-evil").createDirectories()
        assertInstallFailure(InstallFailureReason.InvalidArchive) {
            ArchiveExtractor().extract(evil, ArchiveKind.Zip(), target)
        }
        assertFalse(workspace.resolve("evil.exe").exists())
    }

    @Test
    fun `a raw release file becomes the executable`() {
        val archive = archive("binary".encodeToByteArray(), "download.part")

        ArchiveExtractor().extract(archive, ArchiveKind.Raw("claude"), root)

        assertEquals("binary", root.resolve("claude").readText())
        if (isPosix) assertTrue(root.resolve("claude").isExecutable())
    }

    @Test
    fun `safe targets stay below the root`() {
        assertEquals(root.resolve("a/b"), safeTarget(root, "./a//b"))
        listOf("", "..", "a/..", "/abs", "D:/x").forEach { name ->
            assertInstallFailure(InstallFailureReason.InvalidArchive) { safeTarget(root, name) }
        }
    }

    private fun archive(bytes: ByteArray, name: String = "release.tar.gz"): Path =
        workspace.resolve(name).also { it.writeBytes(bytes) }
}
