package io.aequicor.heartbeat.core.logging

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
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
