package io.aequicor.heartbeat.feature.browser.impl.data

import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarConstants
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrowserRuntimeArchiveTest {
    @Test
    fun `extracts nested files and preserves executable helpers and licenses`() = runTest {
        val root = Files.createTempDirectory("browser-archive")
        try {
            val archive = browserTar(
                listOf(
                    TarArchiveEntry("nested/helper").apply { mode = 493 } to "native".toByteArray(),
                    TarArchiveEntry("JCEF-LICENSE.txt") to "license".toByteArray(),
                ),
            )
            val files = BrowserRuntimeArchive().extract(archive.inputStream(), root)
            assertEquals(setOf("nested/helper", "JCEF-LICENSE.txt"), files.keys)
            assertEquals(browserRuntimeDigest(root.resolve("nested/helper")), files["nested/helper"])
            assertEquals("license", Files.readString(root.resolve("JCEF-LICENSE.txt")))
            assertTrue(Files.isExecutable(root.resolve("nested/helper")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects paths that escape or alias another portable entry`() = runTest {
        val root = Files.createTempDirectory("browser-paths")
        try {
            for (name in listOf(
                "../outside",
                "a/../outside",
                "/outside",
                "C:/outside",
                "a\\outside",
                "a//outside",
                "./outside",
            )) {
                assertFailsWith<IllegalArgumentException>(name) { browserRuntimeTarget(root, name) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects symbolic hard links and special entries`() = runTest {
        for (type in listOf(
            TarConstants.LF_SYMLINK,
            TarConstants.LF_LINK,
            TarConstants.LF_FIFO,
            TarConstants.LF_CHR,
            TarConstants.LF_BLK,
            TarConstants.LF_CONTIG,
        )) {
            val root = Files.createTempDirectory("browser-entry")
            try {
                val entry = TarArchiveEntry("entry", type).apply { linkName = "../outside" }
                val archive = browserTar(listOf(entry to byteArrayOf()))
                assertFailsWith<IllegalArgumentException>("TAR type ${type.toInt().toChar()}") {
                    BrowserRuntimeArchive().extract(archive.inputStream(), root)
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `rejects duplicate files and extraction bombs before publication`() = runTest {
        val root = Files.createTempDirectory("browser-limits")
        try {
            val duplicate = browserTar(List(2) { TarArchiveEntry("same") to byteArrayOf(1) })
            assertFailsWith<IllegalArgumentException> { BrowserRuntimeArchive().extract(duplicate.inputStream(), root) }
            val oversized = browserTar(listOf(TarArchiveEntry("large") to ByteArray(20)))
            assertFailsWith<IllegalArgumentException> {
                BrowserRuntimeArchive(
                    maxBytes = 10,
                ).extract(oversized.inputStream(), root)
            }
            val entries = browserTar(listOf(TarArchiveEntry("other") to byteArrayOf(1)))
            assertFailsWith<IllegalArgumentException> {
                BrowserRuntimeArchive(
                    maxEntries = 0,
                ).extract(entries.inputStream(), root)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
