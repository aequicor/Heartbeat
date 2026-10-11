package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HarnessDetachedCompilationTest {
    @Test
    fun `waiter that times out before its permit withdraws the queued compiler`() = runTest {
        val directory = Files.createTempDirectory("harness-timeout")
        val dispatcher = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val compiler = FakeCompiler()
        val host = JvmHarnessScriptHost(
            directory,
            "test",
            CoroutineScope(job + dispatcher),
            dispatcher.provider(),
            compiler,
        )
        try {
            val waiting = async { host.compile(request()) }
            runCurrent()
            assertEquals(1, dispatcher.pending.size)
            advanceTimeBy(90_000)
            runCurrent()
            assertEquals(HarnessCompilationResult.TimedOut, waiting.await())
            dispatcher.drain()
            assertTrue(job.children.none { it.isActive })
            assertEquals(0, compiler.calls.get())
            assertFalse(Files.list(directory).use { it.findAny().isPresent })
        } finally {
            job.cancel()
            dispatcher.drain()
            job.join()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `compiler exceeding its budget is interrupted and releases the profile permit`() = runTest {
        val directory = Files.createTempDirectory("harness-budget")
        val io = StandardTestDispatcher(testScheduler)
        val thread = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val compiler = FakeCompiler()
        val host = JvmHarnessScriptHost(
            directory,
            "test",
            CoroutineScope(job + io),
            ManualCompilerDispatcher().provider(io),
            compiler,
            thread,
            60.seconds,
        )
        try {
            // The first compiler never gets a thread: it stands for a run that does not return.
            val stuck = async { host.compile(request()) }
            runCurrent()
            val next = async { host.compile(request(ItemId("other"))) }
            runCurrent()
            assertEquals(1, thread.pending.size)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(HarnessCompilationResult.TimedOut, stuck.await())
            assertEquals(2, thread.pending.size)
            thread.pending.removeLast().run()
            runCurrent()
            assertIs<HarnessCompilationResult.Failure>(next.await())
            assertEquals(1, compiler.calls.get())
            // The abandoned run was cancelled before it got a thread, so it never enters the compiler.
            thread.drain()
            assertEquals(1, compiler.calls.get())
        } finally {
            job.cancel()
            thread.drain()
            job.join()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stale temporary compiler outputs are purged once by a new host`() = runTest {
        val directory = Files.createTempDirectory("harness-stale")
        val dispatcher = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val host = JvmHarnessScriptHost(
            directory,
            "test",
            CoroutineScope(job + dispatcher),
            dispatcher.provider(),
            FakeCompiler(),
        )
        try {
            Files.writeString(directory.resolve("compiler-1.jar"), "partial")
            Files.writeString(directory.resolve("compiler-2.json"), "partial")
            Files.writeString(directory.resolve("unrelated.txt"), "kept")
            val waiting = async { host.compile(request()) }
            runCurrent()
            dispatcher.drain()
            runCurrent()
            assertIs<HarnessCompilationResult.Failure>(waiting.await())
            val names = Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() }
            assertEquals(listOf("unrelated.txt"), names)
        } finally {
            job.cancel()
            dispatcher.drain()
            job.join()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mismatched digest misses and broken metadata is removed before recompilation`() = runTest {
        val directory = Files.createTempDirectory("harness-integrity")
        val dispatcher = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val compiler = FakeCompiler()
        val host = JvmHarnessScriptHost(
            directory,
            "test",
            CoroutineScope(job + dispatcher),
            dispatcher.provider(),
            compiler,
        )
        val item = HarnessScriptCache(directory).itemKey(request())
        val key = Json.encodeToString(HarnessScriptClasspath().key(request(), "test"))
        val jar = directory.resolve("$item.jar")
        val metadata = directory.resolve("$item.json")
        try {
            Files.writeString(jar, "not the cached jar")
            // Matching key and kind: only the jar digest disagrees with the metadata.
            Files.writeString(metadata, """{"key":$key,"kind":"Workflow","jarSha":"${"0".repeat(64)}","warnings":[]}""")
            assertIs<HarnessCompilationResult.Failure>(compile(host, dispatcher))
            assertEquals(1, compiler.calls.get())
            assertTrue(Files.exists(jar))
            Files.writeString(metadata, "{")
            assertIs<HarnessCompilationResult.Failure>(compile(host, dispatcher))
            assertEquals(2, compiler.calls.get())
            assertFalse(Files.exists(jar))
            assertFalse(Files.exists(metadata))
        } finally {
            job.cancel()
            dispatcher.drain()
            job.join()
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun TestScope.compile(
        host: JvmHarnessScriptHost,
        dispatcher: ManualCompilerDispatcher,
    ): HarnessCompilationResult {
        val waiting = async { host.compile(request()) }
        runCurrent()
        dispatcher.drain()
        runCurrent()
        return waiting.await()
    }

    @Test
    fun `remove fences queued compiler even after its caller cancels`() = runTest {
        val directory = Files.createTempDirectory("harness-remove")
        val dispatcher = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val host = JvmHarnessScriptHost(directory, "test", CoroutineScope(job + dispatcher), dispatcher.provider())
        try {
            val waiting = async { host.compile(request()) }
            runCurrent()
            waiting.cancelAndJoin()
            val remove = async { host.removeCached(request().harness, request().item) }
            runCurrent()
            // Execute removal before the queued compiler begins; the caller no longer owns its token.
            dispatcher.pending.removeLast().run()
            runCurrent()
            remove.await()
            dispatcher.drain()
            assertFalse(Files.list(directory).use { it.findAny().isPresent })
        } finally {
            job.cancel()
            dispatcher.drain()
            job.join()
            directory.toFile().deleteRecursively()
        }
    }

    private fun request(item: ItemId = ItemId("workflow")) = HarnessCompilationRequest(
        HarnessId("test"),
        item,
        HarnessCodeKind.Workflow,
        "workflow { input -> input }",
    )
}

private class ManualCompilerDispatcher : CoroutineDispatcher() {
    val pending = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        pending.addLast(block)
    }
    fun drain() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
    }
    fun provider(io: CoroutineDispatcher = this): DispatcherProvider = object : DispatcherProvider {
        override val main = io
        override val default = io
        override val io = io
    }
}

/** Counts compiler entries and fails without producing bytecode. */
private class FakeCompiler : HarnessKotlinCompiler {
    val calls = AtomicInteger()
    override fun compile(
        source: SourceCode,
        configuration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<CompiledScript> {
        calls.incrementAndGet()
        return ResultWithDiagnostics.Failure(ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, "fake failure"))
    }
}
