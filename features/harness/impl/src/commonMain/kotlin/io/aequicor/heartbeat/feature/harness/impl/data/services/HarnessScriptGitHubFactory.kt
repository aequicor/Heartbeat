package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.api.script.ScriptProcesses
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHost
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubHostAuthority
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistry
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport
import io.aequicor.heartbeat.feature.harness.impl.domain.github.HarnessScriptGitHub
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

/** Off until host-owned process permissions and production reconciliation are ready. */
internal val HarnessGitHubEnabled = FeatureToggle.Flag(
    key = "harness.github",
    description = "Харнессы: восстановимая синхронизация с GitHub",
    default = false,
)

/** Shared transport/backoff, activation-owned admission and protected durable identity registry. */
@SingleIn(ProfileScope::class)
@Inject
internal class HarnessScriptGitHubFactory(
    transport: GitHubTransport,
    clock: Clock,
    private val origins: HarnessCallOrigins,
    private val toggles: FeatureToggles,
) {
    private val api = GitHubApi(transport, clock)

    fun create(
        access: HarnessInstanceAccess,
        data: ScriptData,
        processes: ScriptProcesses,
        processGate: Mutex,
    ): HarnessScriptGitHub = HarnessScriptGitHub(
        access,
        origins,
        processes,
        GitHubRegistry(data),
        // Script grants cannot authorize merge, release or arbitrary workflow code.
        GitHubHost(api, GitHubHostAuthority { false }) { toggles.get(HarnessGitHubEnabled) },
        processGate,
    )
}
