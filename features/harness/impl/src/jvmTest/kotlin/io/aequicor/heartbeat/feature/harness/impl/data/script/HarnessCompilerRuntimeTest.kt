package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HarnessCompilerRuntimeTest {
    @Test
    fun `development dependencies need no download`() = runTest {
        val cache = RecordingArtifactCache()
        val bundled = listOf(Path.of("development.jar"))
        val runtime = runtime(cache) { bundled }

        assertEquals(bundled, runtime.prepare())
        assertEquals(emptyList(), cache.calls)
    }

    @Test
    fun `every new preparation revalidates the complete pinned cache even offline`() = runTest {
        val cache = RecordingArtifactCache()
        val runtime = runtime(cache)

        assertEquals(paths(), runtime.prepare())
        assertEquals(paths(), runtime.prepare())
        assertEquals(HarnessCompilerArtifacts.artifacts + HarnessCompilerArtifacts.artifacts, cache.calls)
    }

    @Test
    fun `concurrent profiles share setup and cancelling a waiter keeps the app download alive`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cache = RecordingArtifactCache { gate.await() }
        val runtime = runtime(cache)
        val first = async { runtime.prepare() }
        val second = async { runtime.prepare() }
        runCurrent()
        assertEquals(1, cache.calls.size)

        first.cancelAndJoin()
        assertFalse(second.isCompleted)
        gate.complete(Unit)

        assertEquals(paths(), second.await())
        assertEquals(HarnessCompilerArtifacts.artifacts, cache.calls)
    }

    @Test
    fun `failed setup can retry without retaining a failed deferred`() = runTest {
        var isOffline = true
        val cache = RecordingArtifactCache { if (isOffline) throw IOException("offline") }
        val runtime = runtime(cache)

        assertFailsWith<IOException> { runtime.prepare() }
        isOffline = false

        assertEquals(paths(), runtime.prepare())
        assertEquals(HarnessCompilerArtifacts.artifacts.size + 1, cache.calls.size)
    }

    @Test
    fun `app shutdown cancels an active download and its waiting caller`() = runTest {
        val app = SupervisorJob()
        val cache = RecordingArtifactCache { CompletableDeferred<Unit>().await() }
        val runtime = runtime(cache, CoroutineScope(app + StandardTestDispatcher(testScheduler)))
        val waiting = async { runtime.prepare() }
        runCurrent()
        assertEquals(1, cache.calls.size)

        app.cancelAndJoin()

        assertFailsWith<CancellationException> { waiting.await() }
    }

    private fun TestScope.runtime(
        cache: VerifiedArtifactCache,
        scope: CoroutineScope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
        ),
        bundled: () -> List<Path>? = { null },
    ): DownloadedHarnessCompilerRuntime {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        return DownloadedHarnessCompilerRuntime(cache, scope, dispatchers, bundled)
    }

    private fun paths() = HarnessCompilerArtifacts.artifacts.map { Path.of(it.fileName) }
}

private class RecordingArtifactCache(private val before: suspend () -> Unit = {}) : VerifiedArtifactCache {
    val calls = mutableListOf<ReleaseArtifact>()

    override suspend fun prepare(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit): Path {
        calls += artifact
        before()
        return Path.of(artifact.fileName)
    }
}
