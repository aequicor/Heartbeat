package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClaudeProcessTest {
    private fun TestScope.transport(executable: String = java): ProcessClaudeTransport {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val default = testDispatcher
            override val io = Dispatchers.IO
        }
        return ProcessClaudeTransport(dispatchers, ClaudeConfiguration(executable = executable))
    }

    @Test
    fun `cancellation terminates a native child blocked on stdin`() = runTest {
        val program = Files.createTempFile("claude-child", ".java")
        Files.writeString(program, CHILD_PROGRAM)
        val transport = transport()
        val started = CompletableDeferred<Long>()
        try {
            val task = async {
                transport.run(listOf(program.toString()), closeInput = false) { line ->
                    started.complete(line.toLong())
                    false
                }
            }
            val pid = started.await()
            task.cancelAndJoin()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `early stop reports an incomplete input write`() = runTest {
        val program = Files.createTempFile("claude-sleeper", ".java")
        Files.writeString(program, SLEEPER_PROGRAM)
        try {
            val failure = assertFailsWith<EngineException> {
                transport().run(listOf(program.toString()), LARGE_INPUT) { true }
            }
            assertEquals(EngineFailure.Engine(EngineFailureReason.Unavailable), failure.failure)
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `cancellation during a blocked input write stays a cancellation`() = runTest {
        val program = Files.createTempFile("claude-sleeper", ".java")
        Files.writeString(program, SLEEPER_PROGRAM)
        val transport = transport()
        val started = CompletableDeferred<Unit>()
        try {
            val task = async {
                transport.run(listOf(program.toString()), LARGE_INPUT) {
                    started.complete(Unit)
                    false
                }
            }
            started.await()
            task.cancelAndJoin()
            assertFailsWith<CancellationException> { task.await() }
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `operations of one profile run concurrently`() = runTest {
        val program = Files.createTempFile("claude-child", ".java")
        Files.writeString(program, CHILD_PROGRAM)
        val transport = transport()
        val first = CompletableDeferred<Long>()
        val second = CompletableDeferred<Long>()
        try {
            val tasks = listOf(first, second).map { started ->
                async {
                    transport.run(listOf(program.toString()), closeInput = false) { line ->
                        started.complete(line.toLong())
                        false
                    }
                }
            }
            assertNotEquals(first.await(), second.await())
            tasks.forEach { it.cancelAndJoin() }
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `missing executable and script wrappers are unmet requirements`() = runTest {
        val unmet = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)
        for (executable in listOf("heartbeat-missing-claude-binary", "claude.cmd")) {
            val error = assertFailsWith<EngineException> { transport(executable).run(listOf("--version")) { false } }
            assertEquals(unmet, error.failure)
        }
    }

    @Test
    fun `environment drops provider credentials and keeps the CLI default config`() {
        val host = mapOf(
            "PATH" to "/bin",
            "HTTPS_PROXY" to "http://proxy",
            "ANTHROPIC_API_KEY" to "secret",
            "ANTHROPIC_BASE_URL" to "http://elsewhere",
            "CLAUDE_CONFIG_DIR" to "/elsewhere",
        )
        assertEquals(
            mapOf("PATH" to "/bin", "HTTPS_PROXY" to "http://proxy"),
            claudeEnvironment(host, ClaudeConfiguration()),
        )
        assertEquals(
            "/configured",
            claudeEnvironment(host, ClaudeConfiguration(configDirectory = "/configured"))["CLAUDE_CONFIG_DIR"],
        )
    }

    @Test
    fun `arguments disable tools, settings and MCP without quoted JSON`() {
        val arguments = claudeArguments()
        assertTrue(arguments.containsAll(listOf("--tools=", "--setting-sources=", "--strict-mcp-config")))
        assertTrue(arguments.none { '"' in it || it.startsWith("--mcp-config") })
    }
}

private val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()

private const val CHILD_PROGRAM = """
class Child {
    public static void main(String[] args) throws Exception {
        var stdout = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true);
        stdout.println(ProcessHandle.current().pid());
        System.in.read();
    }
}
"""

/** Never reads stdin, so an input larger than the pipe buffer blocks the parent's write. */
private const val SLEEPER_PROGRAM = """
class Sleeper {
    public static void main(String[] args) throws Exception {
        var stdout = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true);
        stdout.println("started");
        Thread.sleep(60_000);
    }
}
"""

private val LARGE_INPUT = "x".repeat(4 * 1024 * 1024)
