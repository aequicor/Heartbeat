package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRun
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessOwnedContext
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.WorkflowRunAccess

/** Library edits to a workflow item cannot revoke its pinned run; harness disable/removal can. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineWorkflowRunAccess(
    private val library: Lazy<HarnessMachine>,
    private val toggles: FeatureToggles,
) : WorkflowRunAccess {
    private val ownership = HarnessOwnedContext()

    override suspend fun isAdmitted(run: WorkflowRun): Boolean {
        if (!toggles.get(HarnessEnabled)) return false
        val state = library.value.state.value as? HarnessState.Ready ?: return false
        if (state.isSuspended || !state.isRuntimeAvailable) return false
        val entry = state.harnesses.singleOrNull { it.harness.id == run.harness } ?: return false
        return entry.harness.isEnabled && state.pending[run.harness] !is HarnessMutation.Remove
    }

    override fun origin(run: WorkflowRun): HarnessCallOrigin? {
        val handoff = run.routing?.handoff ?: return null
        if (handoff.ownerFeature != HARNESS_WAKE_OWNER) return null
        return ownership.decode(handoff.ownerContext)?.takeIf { it.harness == run.harness }?.origin()
    }
}
