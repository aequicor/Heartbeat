package io.aequicor.heartbeat.feature.harness.impl.data.script

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.JavaHarnessCompilerLauncher
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path

/** Optional bootstrap occurs before a caller's compilation wait budget; ordinary bundled test compilers need none. */
internal interface PreparingHarnessCompiler : HarnessKotlinCompiler {
    suspend fun prepare()
}

/** Profile-owned killable worker using app-owned, independently verified compiler dependencies. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<HarnessKotlinCompiler>())
@Inject
internal class DownloadingHarnessCompiler(
    private val runtime: HarnessCompilerRuntime,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
) : PreparingHarnessCompiler {
    private val mutex = Mutex()
    private var compiler: ProcessHarnessCompiler? = null

    override suspend fun prepare() {
        val files = runtime.prepare()
        mutex.withLock {
            if (compiler == null) {
                compiler = ProcessHarnessCompiler(
                    scope.coroutineScope,
                    dispatchers.io,
                    dispatchers.default,
                    JavaHarnessCompilerLauncher(runtimeFiles = files),
                )
            }
        }
    }

    override suspend fun compile(kind: HarnessCodeKind, source: String, output: Path): HarnessCompilerOutcome {
        if (mutex.withLock { compiler == null }) prepare()
        return checkNotNull(mutex.withLock { compiler }).compile(kind, source, output)
    }
}
