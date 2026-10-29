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
