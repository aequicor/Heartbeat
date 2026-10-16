package io.aequicor.heartbeat.core.logging

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotatingFileLogSinkTest {
    @Test
    fun `release file logs preserve Unicode and redact throwable secrets`() = withDirectory { directory ->
        RotatingFileLogSink(directory).use { sink ->
            sink.write(LogLevel.WARNING, "Capture", IllegalStateException("Bearer secret-token"), "Клиентская область")
        }
        val content = logs(directory).joinToString("\n") { Files.readString(it) }
        assertTrue("Клиентская область" in content)
        assertTrue("IllegalStateException" in content)
        assertFalse("secret-token" in content)
    }

    @Test
    fun `rotation is bounded and concurrent sinks have independent files`() = withDirectory { directory ->
        RotatingFileLogSink(directory, maxBytes = 512, fileCount = 3).use { first ->
            RotatingFileLogSink(directory, maxBytes = 512, fileCount = 3).use { second ->
                repeat(30) { first.write(LogLevel.INFO, "First", null, "event $it " + "x".repeat(100)) }
                second.write(LogLevel.INFO, "Second", null, "other process")
            }
        }
        val files = logs(directory)
        assertTrue(files.size <= 6)
        assertTrue(files.any { "other process" in Files.readString(it) })
        assertTrue(files.any { "event 29" in Files.readString(it) })
    }

    @Test
    fun `local sinks keep JUL locks exclusive across maintenance`() = withDirectory { directory ->
        RotatingFileLogSink(directory).use { first ->
            first.write(LogLevel.INFO, "First", null, "owned stream")
            RotatingFileLogSink(directory).use { second ->
                second.write(LogLevel.INFO, "Second", null, "other local stream")
                assertLocksHeld(directory)
                first.removeExpiredRecords()
                assertLocksHeld(directory)
                second.removeExpiredRecords()
                assertLocksHeld(directory)
            }
        }
    }

    private fun assertLocksHeld(directory: Path) {
        val classpath = listOf(DesktopLogLockProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct()
            .joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            DesktopLogLockProbe::class.java.name,
            directory.toString(),
        ).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Lock probe did not terminate")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.exitValue(), output)
        } finally {
            process.destroyForcibly()
        }
    }

    private fun logs(directory: Path): List<Path> = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".log") }.toList()
    }

    private fun withDirectory(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("heartbeat-logs-test")
        try {
            test(directory)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

internal object DesktopLogLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val locks = Files.list(Path.of(args.single())).use { files ->
            files.filter { it.fileName.toString().endsWith(".log.lck") }.toList()
        }
        check(locks.size == 2)
        locks.forEach { path ->
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                channel.tryLock().use { acquired -> check(acquired == null) { "An active JUL lock became available" } }
            }
        }
    }
}
