package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Безопасная распаковка: валидный архив, path traversal, абсолютные пути, symlink, методы, лимиты, truncation. */
class SafeZipExtractorTest {
    private val fs = FileSystem.SYSTEM
    private val temp = Files.createTempDirectory("zip-test").toString().toPath()

    private fun zipOf(name: String, entries: Map<String, ByteArray>): okio.Path {
        val file = temp.resolve(name)
        val zip = ZipOutputStream(Files.newOutputStream(file.toNioPath()))
        entries.forEach { (entryName, content) ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content)
            zip.closeEntry()
        }
        zip.close()
        return file
    }

    @Test
    fun validArchiveExtractsNestedFiles() {
        val archive = zipOf(
            "valid.zip",
            mapOf(
                "runtime/bin/java.dll" to byteArrayOf(1, 2, 3),
                "app/config.json" to "{\"ok\":true}".encodeToByteArray(),
                "app/empty/" to ByteArray(0),
            ),
        )
        val staging = temp.resolve("staging")
        SafeZipExtractor(fs).extract(archive, staging)
        assertEquals(
            listOf<Byte>(1, 2, 3),
            fs.read(staging.resolve("runtime/bin/java.dll")) { readByteArray() }.toList(),
        )
        assertEquals("{\"ok\":true}", fs.read(staging.resolve("app/config.json")) { readUtf8() })
        assertTrue(fs.metadata(staging.resolve("app/empty")).isDirectory)
    }

    @Test
    fun pathTraversalIsRejectedBeforeAnyWrite() {
        val archive = zipOf("evil.zip", mapOf("../evil.txt" to byteArrayOf(1)))
        val staging = temp.resolve("staging-evil")
        assertFailsWith<IllegalArgumentException> { SafeZipExtractor(fs).extract(archive, staging) }
        assertFalse(fs.exists(temp.resolve("evil.txt")))
    }

    @Test
    fun absoluteAndDriveAndBackslashPathsAreRejected() {
        mapOf(
            "/abs.txt" to "absolute",
            "C:/win.txt" to "drive",
            "a\\b.txt" to "backslash",
            "a/./b.txt" to "dot",
        ).forEach { (entry, label) ->
            val archive = zipOf("bad-$label.zip", mapOf(entry to byteArrayOf(1)))
            assertFailsWith<IllegalArgumentException>(label) {
                SafeZipExtractor(fs).extract(archive, temp.resolve("staging-$label"))
            }
        }
    }

    @Test
    fun symlinkIsRejected() {
        val archive = zipOf("link.zip", mapOf("link" to byteArrayOf(1, 2)))
        val bytes = Files.readAllBytes(archive.toNioPath())
        // Помечаем единственную запись центрального каталога как Unix symlink (S_IFLNK << 16, host = Unix).
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val index = bytes.toList().windowed(4).indexOfFirst { it.toByteArray().contentEquals(signature) }
        assertTrue(index > 0)
        bytes[index + 5] = 3
        val attrs = (0xa000 shl 16) or 511
        bytes[index + 38] = (attrs and 0xff).toByte()
        bytes[index + 39] = (attrs ushr 8 and 0xff).toByte()
        bytes[index + 40] = (attrs ushr 16 and 0xff).toByte()
        bytes[index + 41] = (attrs ushr 24 and 0xff).toByte()
        Files.write(archive.toNioPath(), bytes)
        assertFailsWith<IllegalArgumentException> {
            SafeZipExtractor(fs).extract(archive, temp.resolve("staging-link"))
        }
    }

    @Test
    fun executableBitsAreRestoredWithoutPrivilegedBits() {
        if ("posix" !in temp.toNioPath().fileSystem.supportedFileAttributeViews()) return
        val archive = zipOf("executable.zip", mapOf("bin/heartbeat" to byteArrayOf(1)))
        val bytes = Files.readAllBytes(archive.toNioPath())
        val index = bytes.toList().windowed(4).indexOfFirst {
            it.toByteArray().contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        }
        bytes[index + 5] = 3 // Unix host
        val mode = 0x8fff // regular file with setuid/setgid/sticky and all permission bits
        bytes[index + 40] = mode.toByte()
        bytes[index + 41] = (mode ushr 8).toByte()
        Files.write(archive.toNioPath(), bytes)
        val staging = temp.resolve("staging-executable")
        SafeZipExtractor(fs).extract(archive, staging)
        val executable = staging.resolve("bin/heartbeat").toNioPath()
        assertTrue(Files.isExecutable(executable))
        assertEquals(493, (Files.getAttribute(executable, "unix:mode") as Int) and 4095) // 0755 only
    }

    @Test
    fun encryptedEntryIsRejected() {
        val archive = zipOf("encrypted.zip", mapOf("secret" to byteArrayOf(1)))
        val bytes = Files.readAllBytes(archive.toNioPath())
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val index = bytes.toList().windowed(4).indexOfFirst { it.toByteArray().contentEquals(signature) }
        bytes[index + 8] = (bytes[index + 8].toInt() or 1).toByte()
        Files.write(archive.toNioPath(), bytes)
        assertFailsWith<IllegalArgumentException> {
            SafeZipExtractor(fs).extract(archive, temp.resolve("staging-encrypted"))
        }
    }

    @Test
    fun entrySizeLimitIsEnforced() {
        val archive = zipOf("big.zip", mapOf("big" to ByteArray(128)))
        val extractor = SafeZipExtractor(fs, SafeZipExtractor.ZipLimits(maxEntryBytes = 64))
        assertFailsWith<IllegalArgumentException> { extractor.extract(archive, temp.resolve("staging-big")) }
    }

    @Test
    fun totalSizeLimitIsEnforced() {
        val archive = zipOf("many.zip", mapOf("a" to ByteArray(64), "b" to ByteArray(64)))
        val extractor = SafeZipExtractor(fs, SafeZipExtractor.ZipLimits(maxTotalBytes = 100))
        assertFailsWith<IllegalArgumentException> { extractor.extract(archive, temp.resolve("staging-total")) }
    }

    @Test
    fun inflatedDataCannotExceedTheDeclaredEntrySize() {
        val archive = zipOf("oversized-stream.zip", mapOf("payload" to ByteArray(65536) { 1 }))
        val bytes = Files.readAllBytes(archive.toNioPath())
        val central = centralHeader(bytes)
        repeat(4) { bytes[central + 24 + it] = if (it == 0) 1 else 0 }
        Files.write(archive.toNioPath(), bytes)
        val staging = temp.resolve("staging-oversized")
        assertFailsWith<IllegalArgumentException> { SafeZipExtractor(fs).extract(archive, staging) }
        assertTrue(fs.metadata(staging.resolve("payload")).size!! <= 1)
    }

    @Test
    fun earlyDeflateEndWithUnreadCompressedDataIsRejected() {
        val payload = kotlin.random.Random(1).nextBytes(65536)
        val archive = zipOf("early-end.zip", mapOf("payload" to payload))
        val bytes = Files.readAllBytes(archive.toNioPath())
        val nameLength = (bytes[26].toInt() and 255) or ((bytes[27].toInt() and 255) shl 8)
        val extraLength = (bytes[28].toInt() and 255) or ((bytes[29].toInt() and 255) shl 8)
        val dataOffset = 30 + nameLength + extraLength
        val earlyEnd = "cb48cdc9c95728cf2fca490100".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        earlyEnd.copyInto(bytes, dataOffset)
        Files.write(archive.toNioPath(), bytes)
        assertFailsWith<IllegalArgumentException> {
            SafeZipExtractor(
                fs,
            ).extract(archive, temp.resolve("staging-early"))
        }
    }

    private fun centralHeader(bytes: ByteArray): Int = bytes.toList().windowed(4).indexOfFirst {
        it.toByteArray().contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02))
    }

    @Test
    fun cancellationStopsSynchronousInflationBeforeTheNextChunk() {
        val archive = zipOf("cancelled.zip", mapOf("payload" to ByteArray(65536) { 1 }))
        val staging = temp.resolve("staging-cancelled")
        val extractor = SafeZipExtractor(fs, isCancelled = {
            (fs.metadataOrNull(staging.resolve("payload"))?.size ?: 0) > 0
        })
        assertFailsWith<kotlinx.coroutines.CancellationException> { extractor.extract(archive, staging) }
        assertTrue(fs.metadata(staging.resolve("payload")).size!! < 65536)
    }

    @Test
    fun truncatedDeflateStreamIsRejected() {
        val archive = zipOf(
            "truncated.zip",
            mapOf("data.txt" to "some deflated content".repeat(100).encodeToByteArray()),
        )
        val bytes = Files.readAllBytes(archive.toNioPath())
        Files.write(archive.toNioPath(), bytes.copyOf(bytes.size - 64))
        assertFailsWith<Exception> {
            SafeZipExtractor(fs).extract(archive, temp.resolve("staging-truncated"))
        }
    }
}
