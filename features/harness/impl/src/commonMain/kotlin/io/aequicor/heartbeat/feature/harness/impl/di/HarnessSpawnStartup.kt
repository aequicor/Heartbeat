package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSpawnOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** Cleanup starts independently of the harness toggle; no library machine, code or original prompts are loaded. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessSpawnStartup(
    private val operations: Lazy<HarnessSpawnOperations>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    private val log = Log.tag("HarnessSpawns")

    override fun start() {
        val task = profile.coroutineScope.launch {
            while (isActive) {
                try {
                    operations.value.initialize()
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.w(harnessScriptFailure(error)) { "Helper recovery snapshot is unavailable" }
                }
                delay(5.seconds)
            }
        }
        task.invokeOnCompletion { cause ->
            if (cause is CancellationException && profile.coroutineScope.isActive) {
                profile.coroutineScope.launch {
                    delay(5.seconds)
                    start()
                }
            }
        }
    }
}
