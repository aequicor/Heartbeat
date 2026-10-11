package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
            // A non-empty directory cannot be deleted: the purge logs it and goes on with the other outputs.
            Files.createDirectories(directory.resolve("compiler-0")).resolve("kept").let { Files.writeString(it, "") }
            Files.writeString(directory.resolve("compiler-1.jar"), "partial")
            Files.writeString(directory.resolve("compiler-2.json"), "partial")
            Files.writeString(directory.resolve("unrelated.txt"), "kept")
            val waiting = async { host.compile(request()) }
            runCurrent()
            dispatcher.drain()
            runCurrent()
            assertIs<HarnessCompilationResult.Failure>(waiting.await())
            val names = Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() }
            assertEquals(listOf("compiler-0", "unrelated.txt"), names.sorted())
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

    @Test
    fun `admitted compiler that outlives its timed out waiter releases the permit to the next compilation`() = runTest {
        val directory = Files.createTempDirectory("harness-permit")
        val dispatcher = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val compiler = FakeCompiler(CompletableDeferred())
        val host = JvmHarnessScriptHost(
            directory,
            "test",
            CoroutineScope(job + dispatcher),
            dispatcher.provider(),
            compiler,
        )
        try {
            val first = async { host.compile(request()) }
            runCurrent()
            dispatcher.drain()
            assertEquals(1, compiler.calls.get())
            advanceTimeBy(90_000)
            runCurrent()
            assertEquals(HarnessCompilationResult.TimedOut, first.await())
            val second = async { host.compile(request(ItemId("next"))) }
            runCurrent()
            dispatcher.drain()
            // The detached compiler still holds the single permit.
            assertEquals(1, compiler.calls.get())
            checkNotNull(compiler.gate).complete(Unit)
            dispatcher.drain()
            runCurrent()
            dispatcher.drain()
            runCurrent()
            assertEquals(2, compiler.calls.get())
            assertIs<HarnessCompilationResult.Failure>(second.await())
        } finally {
            compiler.gate?.complete(Unit)
            job.cancel()
            dispatcher.drain()
            job.join()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sweep removes orphaned cache files but keeps an item still compiling`() = runTest {
        val directory = Files.createTempDirectory("harness-sweep")
        val dispatcher = ManualCompilerDispatcher()
        val job = SupervisorJob()
        val compiler = FakeCompiler(CompletableDeferred())
        val host = JvmHarnessScriptHost(
            directory,
            "test",
            CoroutineScope(job + dispatcher),
            dispatcher.provider(),
            compiler,
        )
        val cache = HarnessScriptCache(directory)
        val compiling = cache.itemKey(request())
        val kept = cache.itemKey(request(ItemId("kept")))
        val orphan = cache.itemKey(request(ItemId("orphan")))
        try {
            listOf(compiling, kept, orphan).forEach { Files.writeString(directory.resolve("$it.jar"), "jar") }
            val waiting = async { host.compile(request()) }
            runCurrent()
            dispatcher.drain()
            assertEquals(1, compiler.calls.get())
            val sweep = async { host.retainCached(setOf(request().harness to ItemId("kept"))) }
            runCurrent()
            dispatcher.drain()
            runCurrent()
            sweep.await()
            assertTrue(Files.exists(directory.resolve("$compiling.jar")))
            assertTrue(Files.exists(directory.resolve("$kept.jar")))
            assertFalse(Files.exists(directory.resolve("$orphan.jar")))
            checkNotNull(compiler.gate).complete(Unit)
            dispatcher.drain()
            runCurrent()
            assertIs<HarnessCompilationResult.Failure>(waiting.await())
        } finally {
            compiler.gate?.complete(Unit)
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

/** Counts compiler entries and fails without producing bytecode, after [gate] when one is given. */
private class FakeCompiler(val gate: CompletableDeferred<Unit>? = null) : HarnessKotlinCompiler {
    val calls = AtomicInteger()
    override suspend fun compile(kind: HarnessCodeKind, source: String, output: Path): HarnessCompilerOutcome {
        calls.incrementAndGet()
        gate?.await()
        return HarnessCompilerOutcome.Failed(
            listOf(HarnessCodeDiagnostic(HarnessDiagnosticSeverity.Error, "fake failure")),
        )
    }
}
