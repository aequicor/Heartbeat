package io.aequicor.heartbeat.core.logging

import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLogCorruptionTest {
    @Test
    fun `recent text and ZIP recover valid records around malformed bytes and truncated EOF`() = withDirectory { root ->
        val logs = Files.createDirectory(root.resolve("logs"))
        val now = Instant.parse("2026-10-16T12:00:00Z")
        Files.write(
            logs.resolve("heartbeat-0-0.log"),
            record(now.minus(Duration.ofDays(6)), "expired").encodeToByteArray() +
                record(now.minusSeconds(60), "Начало 日本語 token=hidden-value").encodeToByteArray() +
                "java.lang.IllegalStateException: Bearer hidden-token\n".encodeToByteArray() +
                malformedBytes + "\n".encodeToByteArray() +
                record(now.minusSeconds(30), "Конец 😀").encodeToByteArray() +
                "truncated tail ".encodeToByteArray() + truncatedBytes,
        )
        val files = DesktopLogFiles(logs, emptyList(), Clock.fixed(now, ZoneOffset.UTC))
        val recent = files.recentText()
        files.writeRecent(root.resolve("recent.txt"))
        files.writeArchive(root.resolve("logs.zip"))

        assertEquals(recent, Files.readString(root.resolve("recent.txt")))
        assertEquals(recent, archiveText(root.resolve("logs.zip")))
        assertTrue("Начало 日本語 token=***" in recent)
        assertTrue("IllegalStateException: Bearer ***" in recent)
        assertTrue("Конец 😀" in recent)
        assertTrue("truncated tail \uFFFD\n" in recent)
        assertFalse("hidden-value" in recent)
        assertFalse("hidden-token" in recent)
        assertFalse("expired" in recent)
    }

    @Test
    fun `restarts separate new records from damaged EOF and later expire recovered history`() = withDirectory { root ->
        val logs = Files.createDirectory(root.resolve("logs"))
        val now = Instant.parse("2026-10-16T12:00:00Z")
        Files.write(
            logs.resolve("heartbeat-0-0.log"),
            record(now.minusSeconds(660), "older record").encodeToByteArray() +
                "truncated tail ".encodeToByteArray() + truncatedBytes,
        )
        repeat(2) { index ->
            RotatingFileLogSink(logs, 65536, 3, Clock.fixed(now, ZoneOffset.UTC), emptyList()).use { sink ->
                sink.write(LogLevel.INFO, "Test", null, "restart $index")
            }
        }
        val files = DesktopLogFiles(logs, emptyList(), Clock.fixed(now, ZoneOffset.UTC))
        val recent = files.recentText()
        files.writeArchive(root.resolve("restarted.zip"))
        val archive = archiveText(root.resolve("restarted.zip"))
        repeat(2) { index ->
            val newRecord = "$now [INFO] Test - restart $index"
            assertTrue(recent.lineSequence().any { it == newRecord })
            assertTrue(archive.lineSequence().any { it == newRecord })
        }
        assertFalse("older record" in recent)
        assertTrue("older record" in archive)
        assertTrue("truncated tail \uFFFD\n" in archive)

        val later = now.plus(Duration.ofDays(5)).plusSeconds(1)
        RotatingFileLogSink(logs, 65536, 3, Clock.fixed(later, ZoneOffset.UTC), emptyList()).use { sink ->
            sink.write(LogLevel.INFO, "Test", null, "after retention")
        }
        val retained = logFiles(logs).joinToString("") { Files.readString(it) }
        assertTrue("after retention" in retained)
        assertFalse("older record" in retained)
        assertFalse("truncated tail" in retained)
        assertFalse("restart" in retained)
    }

    @Test
    fun `retention scans past malformed bytes and rewrites only the retained records`() = withDirectory { root ->
        val logs = Files.createDirectory(root.resolve("logs"))
        val now = Instant.parse("2026-10-16T12:00:00Z")
        Files.write(
            logs.resolve("heartbeat-0-0.log"),
            record(now.minusSeconds(60), "Здоровая строка token=hidden-value").encodeToByteArray() +
                malformedBytes + "\n".encodeToByteArray() +
                record(now.minus(Duration.ofDays(6)), "expired record").encodeToByteArray() +
                "expired stack\n".encodeToByteArray() +
                record(now.minusSeconds(30), "日本語").encodeToByteArray() + truncatedBytes,
        )
        RotatingFileLogSink(logs, 65536, 3, Clock.fixed(now, ZoneOffset.UTC), emptyList()).use { sink ->
            sink.write(LogLevel.INFO, "Test", null, "after restart")
        }
        val retained = logFiles(logs).joinToString("") { Files.readString(it) }
        assertTrue("Здоровая строка token=***" in retained)
        assertTrue("日本語" in retained)
        assertTrue("\uFFFD\n" in retained)
        assertTrue(retained.lineSequence().any { it == "$now [INFO] Test - after restart" })
        assertFalse("expired record" in retained)
        assertFalse("expired stack" in retained)
        assertFalse("hidden-value" in retained)
    }

    @Test
    fun `missing log files still fail with an IO error`() = withDirectory { root ->
        assertFailsWith<NoSuchFileException> {
            copyLogRecords(root.resolve("missing.log"), StringWriter(), Instant.MIN, Instant.MAX)
        }
    }

    private fun archiveText(path: Path): String = ZipFile(path.toFile()).use { zip ->
        zip.entries().asSequence().joinToString("") { entry ->
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun record(time: Instant, message: String): String = "$time [INFO] Test - $message\n"

    private fun withDirectory(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("heartbeat-log-corruption-test")
        try {
            test(directory)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private val malformedBytes = byteArrayOf(0xFF.toByte(), 0xC3.toByte())
    private val truncatedBytes = byteArrayOf(0xF0.toByte(), 0x9F.toByte())
}
