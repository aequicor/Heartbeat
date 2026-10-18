package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.process.ScriptProcessOwnership
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.PortableGitHubAdapter
import io.aequicor.heartbeat.feature.harness.impl.data.ownership.github.ProtectedImportedGitHub
import io.aequicor.heartbeat.feature.harness.impl.data.run.HarnessProcessWorkflows
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessRunStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRegistry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData

/** Enrollment/explicit transfer opt-in only. Existing protected bindings keep fencing when switched off. */
internal val HarnessProcessOwnershipEnabled = FeatureToggle.Flag(
    "harness.process_ownership",
    "Харнессы: явная передача владения процессом между машинами",
    false,
)

@SingleIn(ProfileScope::class)
@Inject
internal class HarnessScriptOwnershipFactory(
    private val host: OwnershipRuntimeHost,
    private val runs: HarnessRunStorage,
    private val workflows: HarnessProcessWorkflows,
    private val imported: ProtectedImportedGitHub,
    private val toggles: FeatureToggles,
    private val origins: HarnessCallOrigins,
) {
    fun create(
        request: HarnessActivationRequest,
        access: HarnessInstanceAccess,
        scriptData: ScriptData,
    ): ScriptProcessOwnership {
        val hostData = (scriptData as? HarnessScriptData)?.processData() ?: scriptData
        val github = PortableGitHubAdapter(host.runtime.api, GitHubRegistry(hostData), imported)
        val local = RuntimeOwnershipTransferLocal(
            HarnessInstanceTarget(request, access),
            host,
            runs,
            {
                workflows.cancel(
                    io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget(request, access),
                    it,
                )
            },
            github,
            imported,
        )
        return HarnessScriptOwnership(
            HarnessInstanceTarget(request, access),
            host,
            local,
            toggles,
            origins,
        )
    }
}
