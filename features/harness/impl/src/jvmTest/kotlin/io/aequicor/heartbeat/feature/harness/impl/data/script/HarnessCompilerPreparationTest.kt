package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HarnessCompilerPreparationTest {
    private val directory = Files.createTempDirectory("harness-prepare-")
    private val request = HarnessCompilationRequest(
        HarnessId("test"),
        ItemId("workflow"),
        HarnessCodeKind.Workflow,
        "workflow { input -> input }",
    )

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `first use download does not consume the compilation wait budget`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val compiler = PreparingCompiler { gate.await() }
        val host = host(compiler)
        val result = async { host.compile(request) }
        runCurrent()

        advanceTimeBy(120_000)
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0, compiler.calls)
        gate.complete(Unit)

        assertIs<HarnessCompilationResult.Failure>(result.await())
        assertEquals(1, compiler.calls)
    }

    @Test
    fun `item removed during a download cannot publish or compile stale code`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val compiler = PreparingCompiler { gate.await() }
        val host = host(compiler)
        val result = async { host.compile(request) }
        runCurrent()

        host.removeCached(request.harness, request.item)
        gate.complete(Unit)

        assertIs<HarnessCompilationResult.Failure>(result.await())
        assertEquals(0, compiler.calls)
        assertFalse(Files.list(directory).use { it.findAny().isPresent })
    }

    @Test
    fun `setup failure is actionable sanitized and the next attempt retries`() = runTest {
        var isOffline = true
        val compiler = PreparingCompiler { if (isOffline) throw IOException("private path") }
        val host = host(compiler)

        val failed = assertIs<HarnessCompilationResult.Failure>(host.compile(request))
        assertTrue(failed.diagnostics.single().message.contains("retry"))
        assertFalse(failed.diagnostics.single().message.contains("private path"))
        assertEquals(0, compiler.calls)
        isOffline = false

        assertIs<HarnessCompilationResult.Failure>(host.compile(request))
        assertEquals(1, compiler.calls)
    }

    @Test
    fun `profile closure releases an external caller still awaiting preparation`() = runTest {
        val profile = SupervisorJob()
        val compiler = PreparingCompiler { CompletableDeferred<Unit>().await() }
        val host = host(compiler, CoroutineScope(profile + StandardTestDispatcher(testScheduler)))
        val result = async { host.compile(request) }
        runCurrent()

        profile.cancelAndJoin()

        assertFailsWith<CancellationException> { result.await() }
        assertEquals(0, compiler.calls)
    }

    private fun TestScope.host(
        compiler: HarnessKotlinCompiler,
        scope: CoroutineScope = backgroundScope,
    ): JvmHarnessScriptHost {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        return JvmHarnessScriptHost(directory, "test", scope, dispatchers, compiler)
    }
}

private class PreparingCompiler(private val preparation: suspend () -> Unit) : PreparingHarnessCompiler {
    var calls = 0

    override suspend fun prepare() = preparation()

    override suspend fun compile(kind: HarnessCodeKind, source: String, output: Path): HarnessCompilerOutcome {
        calls++
        return HarnessCompilerOutcome.Failed(emptyList())
    }
}
