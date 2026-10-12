package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWorkflowLauncher
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWorkflows
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWorkflowsFactory

@ContributesBinding(ProfileScope::class)
@Inject
internal class StagedHarnessWorkflowsFactory(
    private val origins: HarnessCallOrigins,
    private val launcher: HarnessScriptWorkflowLauncher,
) : HarnessScriptWorkflowsFactory {
    override fun create(request: HarnessActivationRequest, access: HarnessInstanceAccess): HarnessScriptWorkflows =
        HarnessScriptWorkflows(HarnessInstanceTarget(request, access), origins, launcher)
}
