package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            process.onReap = { assertTrue(Files.exists(root), "The cwd must exist until the process has exited") }
            assertEquals(root.toString(), builder.environment()["PI_CODING_AGENT_DIR"])
            assertEquals("1", builder.environment()["PI_OFFLINE"])
            Files.list(root).use { assertEquals(0L, it.count()) }
            process
        }
        assertEquals("1.0.4", version)
        assertTrue(process.isDestroyed)
        assertTrue(process.isReaped)
        assertFalse(Files.exists(checkNotNull(directory)))
    }

    @Test
    fun `failed and timed out probes do not infer a version from output`() = runTest {
        for (process in listOf(VersionProcess(isDone = false), VersionProcess(code = 1))) {
            val startup = PiStartup(Path.of("/custom/pi"), InstallSource.Custom)
            assertNull(readPiRuntimeVersion(startup) { process })
            assertTrue(process.isDestroyed)
            assertTrue(process.isReaped)
        }
    }

    @Test
    fun `cancelled version request still awaits shutdown before removing the cwd`() = runTest {
        var directory: Path? = null
        val process = VersionProcess(isDone = false)
        val probe = async {
            val context = currentCoroutineContext()
            readPiRuntimeVersion(PiStartup(Path.of("/custom/pi"), InstallSource.Custom)) { builder ->
                directory = builder.directory().toPath()
                process.onReap = { assertTrue(Files.exists(checkNotNull(directory))) }
                context.cancel()
                process
            }
        }
        assertFailsWith<CancellationException> { probe.await() }
        assertTrue(process.isDestroyed)
        assertTrue(process.isReaped)
        assertFalse(Files.exists(checkNotNull(directory)))
    }

    @Test
    fun `probe awaits child exit before deleting its working directory`() = runTest {
        var directory: Path? = null
        var child: VersionChild? = null
        val process = VersionProcess(isDone = false)
        assertNull(
            readPiRuntimeVersion(PiStartup(Path.of("/custom/pi"), InstallSource.Custom)) { builder ->
                val root = builder.directory().toPath()
                directory = root
                val descendant = VersionChild(root)
                child = descendant
                process.children = listOf(descendant)
                process.onReap = {
                    assertTrue(descendant.isStopped)
                    assertTrue(descendant.isAlive)
                }
                process
            },
        )
        assertTrue(checkNotNull(child).didExit)
        assertFalse(Files.exists(checkNotNull(directory)))
    }

    private class VersionChild(private val directory: Path) : ProcessHandle by ProcessHandle.current() {
        var isStopped = false
        var didExit = false
        override fun destroyForcibly(): Boolean {
            isStopped = true
            return true
        }
        override fun isAlive(): Boolean = !didExit
        override fun onExit(): CompletableFuture<ProcessHandle> = object : CompletableFuture<ProcessHandle>() {
            override fun get(timeout: Long, unit: TimeUnit): ProcessHandle {
                assertTrue(isStopped)
                assertTrue(Files.exists(directory), "The cwd must survive until descendants have exited")
                assertTrue(unit.toNanos(timeout) in 1..TimeUnit.SECONDS.toNanos(2))
                didExit = true
                return this@VersionChild
            }
        }
    }

    private class VersionProcess(private val isDone: Boolean = true, private val code: Int = 0) : Process() {
        var isDestroyed = false
        var isReaped = false
        var onReap: () -> Unit = {}
        var children: List<ProcessHandle> = emptyList()
        override fun descendants() = children.stream()
        private val output = ByteArrayInputStream("1.0.4".encodeToByteArray())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!isDestroyed) return isDone
            assertTrue(unit.toNanos(timeout) in 1..TimeUnit.SECONDS.toNanos(2))
            onReap()
            isReaped = true
            return true
        }
        override fun exitValue(): Int = code
        override fun destroy() {
            isDestroyed = true
        }
        override fun destroyForcibly(): Process = apply { destroy() }
    }
}
