package io.aequicor.heartbeat.feature.harness.impl.data.script

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/** Resolves the compiler before its execution budget begins; normal cache evaluation never calls this port. */
internal fun interface HarnessCompilerRuntime {
    suspend fun prepare(): List<Path>
}

/** One app-owned setup shared by profiles. Closing a compile waiter does not cancel the component download. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
internal class DownloadedHarnessCompilerRuntime(
    private val cache: VerifiedArtifactCache,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val bundled: () -> List<Path>? = HarnessCompilerArtifacts::bundled,
) : HarnessCompilerRuntime {
    @Inject
    constructor(
        cache: VerifiedArtifactCache,
        @ForScope(AppScope::class) scope: ScopeHandle,
        dispatchers: DispatcherProvider,
    ) : this(cache, scope.coroutineScope, dispatchers)

    private val log = Log.tag("HarnessCompilerRuntime")
    private val mutex = Mutex()
    private var pending: Deferred<List<Path>>? = null

    override suspend fun prepare(): List<Path> = mutex.withLock {
        pending?.takeIf { it.isActive } ?: scope.async(dispatchers.io) {
            bundled() ?: HarnessCompilerArtifacts.artifacts.map { artifact ->
                cache.prepare(artifact)
            }.also { log.d { "Pinned harness compiler components are ready" } }
        }.also { pending = it }
    }.await()
}
