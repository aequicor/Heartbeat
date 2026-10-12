package io.aequicor.heartbeat.feature.plantumlsupport.impl.data.worker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkerJavaArgumentsTest {
    @Test
    fun `argument file outlives start and is removed when the worker exits`() {
        val process = PendingProcess()
        var file: Path? = null
        val actual = startJavaWorker(
            Path.of("java"),
            listOf("-cp", "C:\\a path\\#classes;other", "Worker"),
        ) { command ->
            assertEquals("java", command.first())
            assertEquals(2, command.size)
            file = Path.of(command.last().removePrefix("@"))
            assertTrue(Files.isRegularFile(file))
            process
        }
        val arguments = assertNotNull(file)
        try {
            assertSame(process, actual)
            assertTrue(Files.exists(arguments))
        } finally {
            process.destroy()
        }
        assertFalse(Files.exists(arguments))
    }

    @Test
    fun `failed process creation removes its argument file and preserves the error`() {
        var file: Path? = null
        val failure = IOException("creation failed")
        val actual = assertFailsWith<IOException> {
            startJavaWorker(Path.of("java"), listOf("Worker")) { command ->
                file = Path.of(command.last().removePrefix("@"))
                assertTrue(Files.exists(file))
                throw failure
            }
        }
        assertSame(failure, actual)
        assertFalse(Files.exists(assertNotNull(file)))
    }

    @Test
    fun `arguments quote whitespace backslashes comments and control characters independently of the shell`() {
        val process = PendingProcess()
        try {
            startJavaWorker(
                Path.of("java"),
                listOf("C:\\path with spaces\\", "a\"b#c", "\n\r\t\u000c", ""),
            ) { command ->
                val file = Path.of(command.last().removePrefix("@"))
                val charset = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()))
                assertEquals(
                    "\"C:\\\\path with spaces\\\\\"\n\"a\\\"b#c\"\n\"\\n\\r\\t\\f\"\n\"\"",
                    Files.readString(file, charset),
                )
                process
            }
        } finally {
            process.destroy()
        }
    }

    private class PendingProcess : Process() {
        private val completion = CompletableFuture<Process>()

        override fun onExit(): CompletableFuture<Process> = completion
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = error("Not used")
        override fun exitValue(): Int = if (completion.isDone) 0 else throw IllegalThreadStateException()
        override fun destroy() {
            completion.complete(this)
        }
    }
}
