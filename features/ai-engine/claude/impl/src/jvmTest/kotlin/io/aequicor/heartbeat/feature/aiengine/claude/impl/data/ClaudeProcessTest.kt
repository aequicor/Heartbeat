package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridge
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
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
        return ProcessClaudeTransport(
            dispatchers,
            ClaudeConfiguration(executable = executable),
            object : SearchBridge {
                override fun endpoint() = SearchBridgeEndpoint("http://127.0.0.1:1", "test")
            },
        )
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
            assertExited(pid)
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `cancellation also terminates a helper that inherited stdout`() = runTest {
        val program = Files.createTempFile("claude-tree", ".java")
        Files.writeString(program, TREE_PROGRAM)
        val transport = transport()
        val helper = CompletableDeferred<Long>()
        try {
            val task = async {
                transport.run(listOf(program.toString(), program.toString()), closeInput = false) { line ->
                    helper.complete(line.toLong())
                    false
                }
            }
            val pid = helper.await()
            // Real clock: without the tree kill the reader would block until the helper's own sleep ends.
            withContext(Dispatchers.Default) { withTimeout(HELPER_CANCEL_TIMEOUT_MS) { task.cancelAndJoin() } }
            assertExited(pid)
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `frames read after cancellation are not delivered`() = runTest {
        val program = Files.createTempFile("claude-burst", ".java")
        Files.writeString(program, BURST_PROGRAM)
        val transport = transport()
        val seen = mutableListOf<String>()
        try {
            lateinit var task: Job
            task = async {
                transport.run(listOf(program.toString())) { line ->
                    seen += line
                    task.cancel()
                    false
                }
            }
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(listOf("first"), seen)
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
            val pids = withContext(Dispatchers.Default) {
                withTimeout(CONCURRENT_START_TIMEOUT_MS) { first.await() to second.await() }
            }
            assertNotEquals(pids.first, pids.second)
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

    @Test
    fun `search session receives only local MCP bridge and its two tools`() {
        val config = claudeSearchConfig(
            SearchBridgeEndpoint("http://127.0.0.1:4321", "bridge-token"),
            Files.createTempDirectory("heartbeat-mcp-test"),
        )
        try {
            val arguments = claudeSearchArguments(claudeArguments(search = true), config)
            assertFalse(SEARCH_BRIDGE_MARKER in arguments)
            assertTrue("--strict-mcp-config" in arguments)
            assertTrue(arguments.any { it.startsWith("--tools=mcp__heartbeat_search__web_search") })
            assertEquals(config.toString(), arguments[arguments.indexOf("--mcp-config") + 1])
            val contents = Files.readString(config)
            assertTrue("http://127.0.0.1:4321/mcp" in contents)
            assertTrue("Bearer bridge-token" in contents)
            assertFalse("querit" in contents.lowercase())
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)))
            }
        } finally {
            Files.deleteIfExists(config)
        }
    }
}

private fun assertExited(pid: Long) {
    ProcessHandle.of(pid).ifPresent { it.onExit().get(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
}

private const val EXIT_TIMEOUT_SECONDS = 10L
private const val HELPER_CANCEL_TIMEOUT_MS = 20_000L
private const val CONCURRENT_START_TIMEOUT_MS = 20_000L

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

/** Starts a sleeping helper that shares stdout, prints its pid and keeps running. */
private const val TREE_PROGRAM = """
class Tree {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            Thread.sleep(60_000);
            return;
        }
        var launcher = ProcessHandle.current().info().command().orElseThrow();
        var helper = new ProcessBuilder(launcher, args[0], args[0], "helper")
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        var stdout = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true);
        stdout.println(helper.pid());
        Thread.sleep(60_000);
    }
}
"""

/** Writes two frames at once, so the second is already buffered when the first cancels the operation. */
private const val BURST_PROGRAM = """
class Burst {
    public static void main(String[] args) throws Exception {
        var stdout = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false);
        stdout.print("first\nsecond\n");
        stdout.flush();
        Thread.sleep(60_000);
    }
}
"""

private val LARGE_INPUT = "x".repeat(4 * 1024 * 1024)
