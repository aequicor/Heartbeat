package io.aequicor.heartbeat.feature.harness.impl.data.run

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowOrigin
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessSessionAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptWorkflowLauncher
import kotlinx.serialization.json.JsonObject

/** The script API has no caller/session argument: its workflows are parentless and retain only trusted ancestry. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineHarnessScriptWorkflows(
    private val sessions: HarnessSessionAccess,
    private val launches: Lazy<HarnessWorkflowLaunches>,
) : HarnessScriptWorkflowLauncher {
    override suspend fun start(
        owner: HarnessInstanceTarget,
        name: ItemName,
        input: JsonObject,
        origin: HarnessCallOrigin,
    ): RunId {
        check(!origin.isHookRestricted && sessions.isCurrent(owner)) { "Script workflow launch is unavailable" }
        val candidate = launches.value.prepare(
            owner.request.harness.id,
            name,
            input,
            null,
            null,
            null,
            origin,
            WorkflowOrigin.Script,
        )
        return launches.value.start(candidate) { sessions.isCurrent(owner) }.run
    }
}
