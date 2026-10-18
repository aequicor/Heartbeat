package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.OrganismRequest
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlin.uuid.Uuid

/**
 * [StudioPlanHost] over the studio repository's ports. Closing the planning session in a rollover ends the
 * reflection of its configuration, so the configuration adopted afterwards is the one the next session opens with.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioPlanHost(
    private val access: Lazy<StudioConfigurationAccess>,
    private val runtime: Lazy<StudioRuntime>,
    private val runHost: Lazy<StudioRunHost>,
    private val runs: StudioRunCoordinator,
    private val facade: EngineFacade,
    private val worktrees: StudioWorktrees,
    private val modes: Lazy<StudioChatModes>,
    private val organisms: StudioOrganisms,
) : StudioPlanHost {
    private val log = Log.tag("StudioPlanHost")

    override suspend fun planChat(id: String): StudioChatRecord = access.value.configurationRecord(id)

    override suspend fun validatePlanTarget(id: String, target: EngineTarget) {
        log.d { "Validate the implementation route engine=${target.engine.value}" }
        access.value.validateConfigurationTarget(target)
        if (planChat(id).resolvedExecutionWorkspace() != null) {
            val isLocal = facade.engines.state.value.any {
                it.descriptor.id == target.engine && it.descriptor.isLocalWorkspaceSupported
            }
            check(isLocal) { "Choose a model with local project access" }
        }
    }

    override suspend fun configure(sessionId: String, change: StudioSettingChange): Boolean =
        runtime.value.configure(sessionId, change)

    override suspend fun rolloverForPlan(id: String, settings: RunSettings): RunOutcome {
        log.i { "Continue the planning chat in a new native session for its implementation" }
        val request = StudioTurnRequest(
            id,
            prompt = "",
            settings = settings,
            kind = WorktreeRunKind.Coding,
            request = RequestId("plan-rollover-${Uuid.random()}"),
            isRollover = true,
        )
        return runs.run(runHost.value, request, waitForIdle = true)
    }

    override suspend fun adoptPlanSettings(id: String, settings: StudioSessionSettings) {
        log.i { "Adopt the implementation settings of the planning chat" }
        // The applied state goes first: saving keeps a record whose applied configuration differs from the save.
        access.value.configurationState(id, StudioSessionConfiguration(settings))
        access.value.saveConfiguration(id, settings)
    }

    override suspend fun prepareWorktree(id: String, project: String, isSandbox: Boolean): WorkspaceRef {
        log.i { "Prepare an isolated checkout for the implementation sandbox=$isSandbox" }
        worktrees.send(WorktreeIntent.Public.Prepare(id, WorkspaceRef(project), isSandbox))
        val task = worktrees.await(id) { it.executionWorkspace != null }
        return checkNotNull(task.executionWorkspace)
    }

    override suspend fun admitOrganism(
        id: String,
        goal: String,
        settings: RunSettings,
        projectId: String?,
    ): OrganismAdmission = organisms.admit(id, OrganismRequest(goal, settings, submissionId = ""), false, projectId)

    override suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord) =
        modes.value.move(id, change)

    override suspend fun runPlanTurn(id: String, prompt: String, settings: RunSettings): RunOutcome =
        runtime.value.run(id, prompt, settings)
}
