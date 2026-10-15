package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PiRuntimeArchiveTest {
    @Test
    fun `archive traversal links special entries and duplicate files are rejected`() = runTest {
        piTemporary { root ->
            val malicious = listOf(
                listOf(PiArchiveEntry("pi/../../escaped", "bad")),
                listOf(PiArchiveEntry("../pi", "bad")),
                listOf(PiArchiveEntry("pi/link", type = TarConstants.LF_SYMLINK)),
                listOf(PiArchiveEntry("pi/fifo", type = TarConstants.LF_FIFO)),
                listOf(PiArchiveEntry("pi/pi", "a"), PiArchiveEntry("pi/pi", "b")),
            )
            for (entries in malicious) {
                val target = Files.createTempDirectory(root, "extract-")
                assertFailsWith<IllegalArgumentException> {
                    PiRuntimeArchive().extract(
                        piArchive(root, entries),
                        target,
                        false,
                    )
                }
            }
            assertFalse(Files.exists(root.resolve("escaped")))
        }
    }

    @Test
    fun `expanded byte and entry budgets bound extraction`() = runTest {
        piTemporary { root ->
            val archive = piArchive(root, listOf(PiArchiveEntry("pi/pi", "12345"), PiArchiveEntry("pi/other", "67890")))
            assertFailsWith<IllegalArgumentException> {
                PiRuntimeArchive(maxBytes = 9).extract(archive, Files.createDirectory(root.resolve("bytes")), false)
            }
            assertFailsWith<IllegalArgumentException> {
                PiRuntimeArchive(maxEntries = 1).extract(archive, Files.createDirectory(root.resolve("entries")), false)
            }
        }
    }

    @Test
    fun `windows zip keeps companion assets and refuses unix links and special files`() = runTest {
        piTemporary { root ->
            val archive = root.resolve("pi.zip")
            ZipArchiveOutputStream(archive).use { zip ->
                for (name in listOf("pi.exe", "helper.wasm")) {
                    zip.putArchiveEntry(ZipArchiveEntry(name))
                    zip.write(name.encodeToByteArray())
                    zip.closeArchiveEntry()
                }
            }
            val output = Files.createDirectory(root.resolve("good"))
            PiRuntimeArchive().extract(archive, output, true)
            assertEquals("pi.exe", Files.readString(output.resolve("pi.exe")))
            assertEquals("helper.wasm", Files.readString(output.resolve("helper.wasm")))

            for (mode in listOf(0xA1FF, 0x11FF)) {
                ZipArchiveOutputStream(archive).use { zip ->
                    zip.putArchiveEntry(ZipArchiveEntry("bad").apply { unixMode = mode })
                    zip.closeArchiveEntry()
                }
                assertFailsWith<IllegalArgumentException> {
                    PiRuntimeArchive().extract(archive, Files.createTempDirectory(root, "bad-"), true)
                }
            }
        }
    }
}
