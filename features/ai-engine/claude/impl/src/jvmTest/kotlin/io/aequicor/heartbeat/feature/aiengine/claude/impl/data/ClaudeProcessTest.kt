package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridge
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeAttachment
import io.aequicor.heartbeat.feature.searchengine.api.SearchBridgeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.io.StringReader
import java.io.Writer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClaudeProcessTest {
    // Native process pipes and deadlines need a real clock; release the pool after each test.
    private val nativeDispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @AfterTest
    fun closeNativeDispatcher() {
        nativeDispatcher.close()
    }

    private fun TestScope.transport(
        executable: String = java,
        launches: EngineLaunchConfig = EngineLaunchConfig.Default,
    ): ProcessClaudeTransport {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val default = testDispatcher
            override val io = nativeDispatcher
        }
        return ProcessClaudeTransport(
            dispatchers,
            ClaudeConfiguration(executable = executable),
            object : SearchBridge {
                override fun endpoint() = SearchBridgeEndpoint("http://127.0.0.1:1", "test")
                override fun attach(features: EngineFeatures) = SearchBridgeAttachment { }
            },
            TestLocalWorkspaces(),
            launches,
        )
    }

    @Test
    fun `duplex writes concurrent whole frames and supports explicit idempotent EOF`() = runTest {
        val program = Files.createTempFile("claude-duplex", ".java")
        Files.writeString(program, DUPLEX_PROGRAM)
        try {
            val exit = transport().duplex(listOf(program.toString())) { pipe ->
                val frames = (1..2).map {
                    JsonObject(
                        mapOf("id" to JsonPrimitive(it), "text" to JsonPrimitive("x".repeat(8000))),
                    )
                }
                coroutineScope { frames.map { frame -> async { pipe.send(frame) } }.forEach { it.await() } }
                assertEquals(frames.toSet(), setOf(pipe.receive(), pipe.receive()))
                pipe.closeInput()
                pipe.closeInput()
                assertEquals(null, pipe.receive())
                assertFailsWith<EngineException> { pipe.send(frames.first()) }
            }
            assertEquals(0, exit)
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `duplex cancellation unblocks both native pipes and never leaks the process`() = runTest {
        val program = Files.createTempFile("claude-duplex-block", ".java")
        Files.writeString(program, DUPLEX_BLOCKED_PROGRAM)
        val started = CompletableDeferred<Long>()
        try {
            val task = async {
                transport().duplex(listOf(program.toString())) { pipe ->
                    coroutineScope {
                        val write = async {
                            pipe.send(JsonObject(mapOf("payload" to JsonPrimitive("x".repeat(1024 * 1024)))))
                        }
                        started.complete(pipe.receive()!!.text("pid")!!.toLong())
                        write.await()
                    }
                }
            }
            val pid = started.await()
            withContext(nativeDispatcher) { withTimeout(HELPER_CANCEL_TIMEOUT_MS) { task.cancelAndJoin() } }
            assertFailsWith<CancellationException> { task.await() }
            assertExited(pid)
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `duplex rejects malformed native JSON without exposing its contents`() = runTest {
        val program = Files.createTempFile("claude-duplex-bad", ".java")
        Files.writeString(program, BURST_PROGRAM)
        try {
            val error = assertFailsWith<EngineException> {
                transport().duplex(listOf(program.toString())) { it.receive() }
            }
            assertFalse(error.toString().contains("first"))
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `duplex rejects oversized frames and overflowing queues instead of blocking the reader`() = runTest {
        val program = Files.createTempFile("claude-duplex-overflow", ".java")
        Files.writeString(program, DUPLEX_OVERFLOW_PROGRAM)
        try {
            for (mode in listOf("oversized", "overflow")) {
                val error = assertFailsWith<EngineException> {
                    transport().duplex(listOf(program.toString(), mode)) { awaitCancellation() }
                }
                assertEquals(EngineFailure.Transport(TransportFailureReason.ProtocolViolation), error.failure)
            }
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `duplex reports a failed flush even if a result has already arrived`() = runTest {
        val program = Files.createTempFile("claude-duplex-closed", ".java")
        Files.writeString(program, DUPLEX_CLOSED_PROGRAM)
        try {
            val error = assertFailsWith<EngineException> {
                transport().duplex(listOf(program.toString())) { pipe ->
                    assertEquals("result", pipe.receive()!!.text("type"))
                    pipe.send(JsonObject(mapOf("payload" to JsonPrimitive("x".repeat(1024 * 1024)))))
                }
            }
            assertEquals(EngineFailure.Engine(EngineFailureReason.Unavailable), error.failure)
        } finally {
            Files.deleteIfExists(program)
        }
    }

    @Test
    fun `cancelling an EOF waiter does not revoke the committed close`() = runTest {
        withContext(nativeDispatcher) {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                var isClosed = false
                val output = object : Writer() {
                    override fun write(chars: CharArray, offset: Int, count: Int) = Unit
                    override fun flush() {
                        entered.complete(Unit)
                        release.await()
                    }
                    override fun close() {
                        isClosed = true
                    }
                }
                val pipe = ClaudeDuplexPipe(this, BufferedWriter(output), StringReader("").buffered())
                try {
                    val write = async { pipe.send(JsonObject(emptyMap())) }
                    entered.await()
                    val closing = async(start = CoroutineStart.UNDISPATCHED) { pipe.closeInput() }
                    closing.cancelAndJoin()
                    release.countDown()
                    pipe.closeInput()
                    write.await()
                    assertTrue(isClosed)
                } finally {
                    release.countDown()
                    pipe.revoke()
                }
            }
        }
    }

    @Test
    fun `duplex cancellation terminates a process that closed stdout but has not exited`() = runTest {
        val program = Files.createTempFile("claude-duplex-no-stdout", ".java")
        Files.writeString(program, DUPLEX_NO_STDOUT_PROGRAM)
        val drained = CompletableDeferred<Long>()
        try {
            val task = async {
                transport().duplex(listOf(program.toString())) { pipe ->
                    val pid = pipe.receive()!!.text("pid")!!.toLong()
                    pipe.closeInput()
                    assertEquals(null, pipe.receive())
                    drained.complete(pid)
                }
            }
            val pid = drained.await()
            withContext(nativeDispatcher) { withTimeout(HELPER_CANCEL_TIMEOUT_MS) { task.cancelAndJoin() } }
            assertExited(pid)
        } finally {
            Files.deleteIfExists(program)
        }
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
            withContext(nativeDispatcher) { withTimeout(HELPER_CANCEL_TIMEOUT_MS) { task.cancelAndJoin() } }
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
            val pids = withContext(nativeDispatcher) {
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
    fun `a pinned transport keeps its executable and native history when the launch changes`() = runTest {
        var launch = LaunchContext(LaunchSettings(executable = java, homeDirectory = "/tmp/claude-a"))
        val transport = transport(launches = EngineLaunchConfig { launch })
        val pinned = transport.pinned()
        launch = LaunchContext(
            LaunchSettings(executable = "/heartbeat/missing/claude", homeDirectory = "/tmp/claude-b"),
        )

        assertEquals(0, pinned.run(listOf("--version")) { false })
        val error = assertFailsWith<EngineException> { transport.run(listOf("--version")) { false } }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), error.failure)
        assertEquals(claudeNativeStore("/tmp/claude-a"), pinned.nativeStore)
        assertEquals(claudeNativeStore("/tmp/claude-b"), transport.pinned().nativeStore)
    }

    @Test
    fun `locating reads the version of the executable the launch names`() = runTest {
        if (File.separatorChar == '\\') return@runTest
        val directory = Files.createTempDirectory("claude-locate")
        try {
            val claude = directory.resolve("claude")
            Files.writeString(claude, "#!/bin/sh\necho '2.1.285 (Claude Code)'\n")
            claude.toFile().setExecutable(true)

            val transport = transport()
            // The probe deadline needs a real clock, like the process pipes.
            val found = withContext(nativeDispatcher) {
                transport.locate(LaunchContext(LaunchSettings(executable = claude.toString())))
            }

            assertEquals(Installation(InstallSource.Custom, "2.1.285", claude.toString()), found)
            val missing = withContext(nativeDispatcher) {
                transport.locate(LaunchContext(LaunchSettings(executable = "/heartbeat/missing/claude")))
            }
            assertEquals(Installation(InstallSource.Custom, null, "/heartbeat/missing/claude"), missing)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `arguments disable tools, settings and MCP without quoted JSON`() {
        val arguments = claudeArguments()
        assertTrue(arguments.containsAll(listOf("--tools=", "--setting-sources=", "--strict-mcp-config")))
        assertTrue(arguments.none { '"' in it || it.startsWith("--mcp-config") })
    }

    @Test
    fun `search session receives native web tools and only the local MCP bridge`() {
        val config = claudeSearchConfig(
            SearchBridgeEndpoint("http://127.0.0.1:4321", "bridge-token"),
            Files.createTempDirectory("heartbeat-mcp-test"),
        )
        try {
            val arguments = claudeSearchArguments(claudeArguments(search = true), config)
            assertFalse(SEARCH_BRIDGE_MARKER in arguments)
            assertTrue("--strict-mcp-config" in arguments)
            val tools = arguments.single { it.startsWith("--tools=") }.removePrefix("--tools=").split(',')
            val allowed = arguments.single { it.startsWith("--allowedTools=") }.removePrefix("--allowedTools=")
            assertEquals(
                listOf(
                    "WebSearch",
                    "mcp__heartbeat_search__web_search",
                    "mcp__heartbeat_search__web_fetch",
                ),
                tools,
            )
            assertEquals(tools, allowed.split(','))
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

private const val DUPLEX_PROGRAM = """
class Duplex {
    public static void main(String[] args) throws Exception {
        var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        var output = new java.io.PrintWriter(System.out, true);
        String line;
        while ((line = input.readLine()) != null) output.println(line);
    }
}
"""

private const val DUPLEX_BLOCKED_PROGRAM = """
class DuplexBlocked {
    public static void main(String[] args) throws Exception {
        System.in.read();
        System.out.println("{\"pid\":" + ProcessHandle.current().pid() + "}");
        System.out.flush();
        Thread.sleep(60_000);
    }
}
"""

private const val DUPLEX_OVERFLOW_PROGRAM = """
class DuplexOverflow {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("oversized")) System.out.println("x".repeat(2 * 1024 * 1024 + 1));
        else for (int i = 0; i < 1000; i++) System.out.println("{}");
        System.out.flush();
        Thread.sleep(60_000);
    }
}
"""

private const val DUPLEX_CLOSED_PROGRAM = """
class DuplexClosed {
    public static void main(String[] args) throws Exception {
        System.in.close();
        System.out.println("{\"type\":\"result\"}");
        System.out.flush();
        Thread.sleep(60_000);
    }
}
"""

private const val DUPLEX_NO_STDOUT_PROGRAM = """
class DuplexNoStdout {
    public static void main(String[] args) throws Exception {
        System.out.println("{\"pid\":" + ProcessHandle.current().pid() + "}");
        System.out.close();
        Thread.sleep(60_000);
    }
}
"""
