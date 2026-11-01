package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekConfiguration
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekEngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.withContext

/**
 * Management view of the user-installed `dsh` CLI: the same discovery the engine runs with, plus its version. The
 * adapter never installs, updates or replaces the CLI — the user's DeepSeek Harness installation owns it.
 */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class JvmDeepSeekManager(
    private val dispatchers: DispatcherProvider,
    private val configuration: DeepSeekConfiguration = DeepSeekConfiguration(),
    private val host: DeepSeekHost = DeepSeekHost(),
) : DeepSeekEngineManager {
    private val log = Log.tag("DeepSeekManager")

    /**
     * The executable the next session would start and its version. The path is reported for display only and never
     * logged; a missing installation is certain non-installation, not a failed probe.
     */
    override suspend fun inspect(launch: LaunchContext): Installation = withContext(dispatchers.io) {
        val startup = resolveDeepSeekStartup(configuration, host)
        if (startup.source == InstallSource.Missing) {
            log.i { "DeepSeek Harness CLI is not installed" }
            return@withContext Installation(InstallSource.Missing, isRunnable = false)
        }
        val version = if (startup.isRunnable) {
            probeDeepSeekVersion(dispatchers, startup.executable, PROBE_TIMEOUT_MS)
        } else {
            null
        }
        log.i { "DeepSeek Harness located source=${startup.source} version=${version ?: "unknown"}" }
        Installation(startup.source, version, startup.executable, startup.isRunnable)
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 10_000L
    }
}
