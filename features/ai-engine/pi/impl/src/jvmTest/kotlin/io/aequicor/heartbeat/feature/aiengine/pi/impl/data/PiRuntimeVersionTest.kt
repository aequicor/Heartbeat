package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiRuntimeVersionTest {
    @Test
    fun `custom version probe isolates settings and project content then removes its directory`() = runTest {
        var directory: Path? = null
        val process = VersionProcess()
        val startup = PiStartup(
            Path.of("/custom/pi"),
            InstallSource.Custom,
            environment = listOf(EnvironmentEntry("PI_CODING_AGENT_DIR", "/unsafe")),
        )
        val version = readPiRuntimeVersion(startup) { builder ->
            assertEquals(listOf("/custom/pi", "--version"), builder.command().map { it.replace('\\', '/') })
            val root = builder.directory().toPath()
            directory = root
            assertEquals(root.toString(), builder.environment()["PI_CODING_AGENT_DIR"])
            assertEquals("1", builder.environment()["PI_OFFLINE"])
            Files.list(root).use { assertEquals(0L, it.count()) }
            process
        }
        assertEquals("1.0.4", version)
        assertTrue(process.isDestroyed)
        assertFalse(Files.exists(checkNotNull(directory)))
    }

    @Test
    fun `failed and timed out probes do not infer a version from output`() = runTest {
        for (process in listOf(VersionProcess(isDone = false), VersionProcess(code = 1))) {
            val startup = PiStartup(Path.of("/custom/pi"), InstallSource.Custom)
            assertNull(readPiRuntimeVersion(startup) { process })
            assertTrue(process.isDestroyed)
        }
    }

    private class VersionProcess(private val isDone: Boolean = true, private val code: Int = 0) : Process() {
        var isDestroyed = false
        private val output = ByteArrayInputStream("1.0.4".encodeToByteArray())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = isDone
        override fun exitValue(): Int = code
        override fun destroy() {
            isDestroyed = true
        }
        override fun destroyForcibly(): Process = apply { destroy() }
    }
}
