package io.aequicor.heartbeat.core.logging

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLogFilesTest {
    @Test
    fun `recent export includes exact boundaries and sorts complete Unicode records`() = withDirectory { root ->
        val logs = Files.createDirectory(root.resolve("logs"))
        val now = Instant.parse("2026-10-16T12:00:00Z")
        val boundary = now.minusSeconds(600)
        Files.writeString(
            logs.resolve("heartbeat-0-1.log"),
            record(boundary.minusNanos(1), "expired", "expired stack\n") +
                record(
                    boundary,
                    "Граница",
                    "java.lang.IllegalStateException: Bearer hidden-token\n\tat Call.invoke(Call.kt:1)\n",
                ),
        )
        Files.writeString(logs.resolve("heartbeat-0-0.log"), record(now, "latest"))
        Files.writeString(logs.resolve("heartbeat-1-0.log"), record(now.minusSeconds(30), "middle"))
        Files.writeString(logs.resolve("heartbeat-2-0.log"), record(now.plusNanos(1), "future"))
        val files = DesktopLogFiles(logs, emptyList(), Clock.fixed(now, ZoneOffset.UTC))

        val text = files.recentText()
        assertFalse("expired" in text)
        assertFalse("future" in text)
        assertFalse("hidden-token" in text)
        assertTrue("IllegalStateException: Bearer ***\n\tat Call.invoke(Call.kt:1)" in text)
        assertTrue(text.indexOf("Граница") < text.indexOf("middle"))
        assertTrue(text.indexOf("middle") < text.indexOf("latest"))
        files.writeRecent(root.resolve("recent.txt"))
        assertEquals(text, Files.readString(root.resolve("recent.txt")))
    }

    @Test
    fun `restart removes expired records despite recent mtime while preserving complete recent stacks`() =
        withDirectory { root ->
            val now = Instant.parse("2026-10-16T12:00:00Z")
            val file = root.resolve("heartbeat-0-0.log")
            Files.writeString(
                file,
                record(now.minus(Duration.ofDays(5)).minusNanos(1), "old", "old stack\n") +
                    record(now.minus(Duration.ofDays(5)), "boundary", "boundary stack\n") +
                    record(now, "retained", "retained stack\n"),
            )
            Files.setLastModifiedTime(file, FileTime.from(now))
            RotatingFileLogSink(root, 4096, 3, Clock.fixed(now, ZoneOffset.UTC), emptyList()).use { sink ->
                sink.write(LogLevel.INFO, "Test", null, "after restart")
            }
            val content = logFiles(root).joinToString("") { Files.readString(it) }
            assertFalse("old" in content)
            assertTrue("boundary\nboundary stack" in content)
            assertTrue("retained\nretained stack" in content)
            assertTrue("after restart" in content)
        }

    @Test
    fun `long running sink maintenance expires its own records and continues writing`() = withDirectory { root ->
        val clock = MutableLogClock(Instant.parse("2026-10-10T12:00:00Z"))
        RotatingFileLogSink(root, 4096, 3, clock, emptyList()).use { sink ->
            sink.write(LogLevel.INFO, "Test", null, "expired active record")
            clock.current = clock.current.plus(Duration.ofDays(5)).plusSeconds(1)
            sink.write(LogLevel.INFO, "Test", null, "recent active record")
            sink.removeExpiredRecords()
            sink.write(LogLevel.INFO, "Test", null, "writer still works")
            val content = logFiles(root).joinToString("") { Files.readString(it) }
            assertFalse("expired active record" in content)
            assertTrue("recent active record" in content)
            assertTrue("writer still works" in content)
        }
    }

    @Test
    fun `archive includes only retained recognized files and filters active legacy records`() = withDirectory { root ->
        val logs = Files.createDirectory(root.resolve("logs"))
        val legacy = Files.createDirectory(root.resolve("legacy"))
        val now = Instant.parse("2026-10-16T12:00:00Z")
        val clock = MutableLogClock(now.minus(Duration.ofDays(6)))
        RotatingFileLogSink(logs, 4096, 3, clock, emptyList()).use { sink ->
            sink.write(LogLevel.INFO, "Test", null, "expired active record")
            clock.current = now
            sink.write(LogLevel.WARNING, "Test", IllegalStateException("sk-abcdefghijk"), "日本語")
            Files.writeString(legacy.resolve("heartbeat-7-0.log"), record(now.minusSeconds(30), "legacy token=hidden"))
            Files.writeString(logs.resolve("secrets.log"), "secret unrelated file")
            Files.writeString(logs.resolve("heartbeat-8-0.log.lck"), "lock contents")
            val external = Files.writeString(root.resolve("external.txt"), "external private data")
            if (!System.getProperty("os.name").startsWith("Windows")) {
                Files.createSymbolicLink(logs.resolve("heartbeat-9-0.log"), external)
            }
            val destination = root.resolve("logs.zip")
            DesktopLogFiles(logs, listOf(legacy), Clock.fixed(now, ZoneOffset.UTC)).writeArchive(destination)

            ZipFile(destination.toFile()).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(2, entries.size)
                val content = entries.joinToString(
                    "",
                ) { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
                assertTrue("日本語" in content)
                assertTrue("IllegalStateException" in content)
                assertTrue("legacy token=***" in content)
                assertFalse("expired active record" in content)
                assertFalse("sk-abcdefghijk" in content)
                assertFalse("secret unrelated file" in content)
                assertFalse("lock contents" in content)
                assertFalse("external private data" in content)
            }
            assertTrue("expired active record" in logFiles(logs).joinToString("") { Files.readString(it) })
        }
    }

    @Test
    fun `empty and future-only logs create a valid empty archive`() = withDirectory { root ->
        val logs = Files.createDirectory(root.resolve("logs"))
        val now = Instant.parse("2026-10-16T12:00:00Z")
        Files.writeString(logs.resolve("heartbeat-0-0.log"), record(now.plusSeconds(1), "future"))
        val files = DesktopLogFiles(logs, emptyList(), Clock.fixed(now, ZoneOffset.UTC))
        assertEquals("", files.recentText())
        files.writeArchive(root.resolve("empty.zip"))
        ZipFile(root.resolve("empty.zip").toFile()).use { assertEquals(0, it.size()) }
    }

    @Test
    fun `export refuses a symlink destination inside storage`() = withDirectory { root ->
        if (System.getProperty("os.name").startsWith("Windows")) return@withDirectory
        val logs = Files.createDirectory(root.resolve("logs"))
        val alias = Files.createSymbolicLink(root.resolve("alias"), logs)
        val files = DesktopLogFiles(logs, emptyList())
        assertFailsWith<IllegalArgumentException> { files.writeArchive(alias.resolve("archive.zip")) }
        assertFailsWith<IllegalArgumentException> { files.writeRecent(alias.resolve("recent.txt")) }
    }

    @Test
    fun `legacy export protection applies before the new log directory exists`() = withDirectory { root ->
        val legacy = Files.createDirectory(root.resolve("legacy"))
        val target = legacy.resolve("heartbeat-0-0.log")
        val now = Instant.now()
        val original = record(now, "legacy")
        Files.writeString(target, original)
        val files = DesktopLogFiles(root.resolve("future-logs"), listOf(legacy))

        assertFailsWith<IllegalArgumentException> { files.validateExportDestination(target) }
        assertEquals(original, Files.readString(target))
        assertTrue("legacy" in files.recentText())
    }

    @Test
    fun `parallel rotation and archives preserve valid complete records`() = withDirectory { root ->
        val logs = Files.createDirectory(root.resolve("logs"))
        val files = DesktopLogFiles(logs, emptyList())
        val executor = Executors.newFixedThreadPool(2)
        try {
            RotatingFileLogSink(logs, 512, 3).use { sink ->
                val writes = executor.submit {
                    repeat(100) { sink.write(LogLevel.INFO, "Test", IllegalStateException("trace"), "event $it") }
                }
                val exports = executor.submit {
                    repeat(15) { index ->
                        val target = root.resolve("$index.zip")
                        files.writeArchive(target)
                        ZipFile(target.toFile()).use { zip ->
                            zip.entries().asSequence().forEach { entry ->
                                val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                                assertTrue(text.endsWith("\n"))
                                assertTrue("IllegalStateException: trace" in text)
                            }
                        }
                    }
                }
                writes.get()
                exports.get()
                assertTrue("event 99" in files.recentText())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun record(time: Instant, message: String, stack: String = ""): String =
        "$time [INFO] Test - $message\n$stack"

    private fun withDirectory(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("heartbeat-log-export-test")
        try {
            test(directory)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

private class MutableLogClock(
    @Volatile var current: Instant,
) : Clock() {
    override fun instant(): Instant = current
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
