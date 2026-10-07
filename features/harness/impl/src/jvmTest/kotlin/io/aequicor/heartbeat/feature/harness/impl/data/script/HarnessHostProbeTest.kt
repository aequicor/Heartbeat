package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.CompiledHarnessCode
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationResult
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessHostProbeTest {
    @Test
    fun `production probe compiles both templates and reloads their artifacts with every resource closed`() {
        val directory = Files.createTempDirectory("harness-probe-test-")
        try {
            assertEquals(
                HARNESS_PROBE_SUCCESS,
                HarnessHostProbe.run(
                    directory.toString(),
                    "probe-test",
                ).toCompletableFuture().get(240, TimeUnit.SECONDS),
            )
        } finally {
            // On Windows this also rejects a false-success stage that left a compiled JAR open.
            deleteProbeDirectory(directory)
        }
    }

    @Test
    fun `failure is sanitized and completion waits for actual noncancellable compiler work to finish`() {
        val directory = Files.createTempDirectory("harness-probe-drain-")
        val started = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = AtomicReference<Job>()
        val completion = runHarnessProbe(directory.toString(), "probe-test") { _, _, scope, _ ->
            owner.set(checkNotNull(scope.coroutineContext[Job]))
            object : HarnessScriptHost {
                override val isAvailable = true
                override suspend fun compile(request: HarnessCompilationRequest): HarnessCompilationResult {
                    scope.launch {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            started.countDown()
                            release.await()
                        }
                    }
                    entered.await()
                    error("private source and path must never escape")
                }
                override suspend fun evaluate(
                    code: CompiledHarnessCode,
                    context: HarnessEvaluationContext,
                ): HarnessEvaluationResult = error("unexpected evaluation")
                override suspend fun removeCached(harness: HarnessId, item: ItemId): Unit = error("unexpected removal")
            }
        }.toCompletableFuture()
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertFalse(completion.isDone)
            release.complete(Unit)
            val failed = assertFailsWith<ExecutionException> { completion.get(10, TimeUnit.SECONDS) }
            val cause = checkNotNull(failed.cause)
            assertEquals("HarnessCompilerProbeFailed:Execution", cause.message)
            assertNull(cause.cause)
            assertTrue(owner.get().isCompleted)
        } finally {
            release.complete(Unit)
            deleteProbeDirectory(directory)
        }
    }
}

private fun deleteProbeDirectory(directory: Path) {
    Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
