package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
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
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProjects
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreePhase
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.flow.first
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [StudioPlanHost] over the studio repository's ports. Closing the planning session in a rollover ends the
 * reflection of its configuration, so the configuration adopted afterwards is the one the next session opens with.
 * The reservation is the one of the studio's turns: the rollover and the first turn execute inside it directly.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioPlanHost(
    private val access: Lazy<StudioConfigurationAccess>,
    private val runtime: Lazy<StudioRuntime>,
    private val runHost: Lazy<StudioRunHost>,
    private val turnHost: Lazy<StudioTurnHost>,
    private val runs: StudioRunCoordinator,
    private val facade: EngineFacade,
    private val worktrees: StudioWorktrees,
    private val modes: Lazy<StudioChatModes>,
    private val organisms: StudioOrganisms,
    private val projects: StudioProjects,
    private val toggles: FeatureToggles,
) : StudioPlanHost {
    private val log = Log.tag("StudioPlanHost")

    override suspend fun planChat(id: String): StudioChatRecord = access.value.configurationRecord(id)

    override suspend fun validatePlanTarget(id: String, target: EngineTarget, checkout: PlanCheckout?) {
        log.d { "Validate the implementation route engine=${target.engine.value} checkout=${checkout ?: "none"}" }
        access.value.validateConfigurationTarget(target)
        if (checkout != null) {
            val refusal = checkoutRefusal(
                checkout,
                isWorktreeAvailable = projects.worktreeAvailability.first(),
                isSandboxAvailable = projects.sandboxAvailability.first(),
                isRepository = planChat(id).projectId in projects.repositoryProjects.first(),
            )
            check(refusal == null) { refusal.orEmpty() }
        }
        if (planChat(id).resolvedExecutionWorkspace() != null) {
            val isLocal = facade.engines.state.value.any {
                it.descriptor.id == target.engine && it.descriptor.isLocalWorkspaceSupported
            }
            check(isLocal) { "Choose a model with local project access" }
        }
    }

    override suspend fun admitOrganism(
        id: String,
        goal: String,
        settings: RunSettings,
        projectId: String?,
        isWorktree: Boolean,
    ): OrganismAdmission {
        check(toggles.get(OrganicAiEnabled)) { "Organic AI is turned off" }
        return organisms.admit(id, OrganismRequest(goal, settings, submissionId = ""), isWorktree, projectId)
    }

    override suspend fun reserve(id: String, settings: RunSettings, body: suspend () -> RunOutcome): RunOutcome {
        log.i { "Reserve the planning chat for its implementation" }
        val request = StudioTurnRequest(
            id,
            prompt = "",
            settings = settings,
            kind = WorktreeRunKind.Coding,
            request = RequestId("plan-implementation-${Uuid.random()}"),
        )
        return runs.run(PlanRunHost(runHost.value, body), request)
    }

    override suspend fun isStopRequested(id: String): Boolean = turnHost.value.shouldStop(id)

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
        return runHost.value.executeRun(request)
    }

    override suspend fun adoptPlanSettings(id: String, settings: StudioSessionSettings) {
        log.i { "Adopt the implementation settings of the planning chat" }
        // The applied state goes first: saving keeps a record whose applied configuration differs from the save.
        access.value.configurationState(id, StudioSessionConfiguration(settings))
        access.value.saveConfiguration(id, settings)
    }

    override suspend fun prepareWorktree(id: String, project: String, isSandbox: Boolean): StudioIsolation {
        log.i { "Prepare an isolated checkout for the implementation sandbox=$isSandbox" }
        worktrees.send(WorktreeIntent.Public.Prepare(id, WorkspaceRef(project), isSandbox))
        val task = worktrees.await(id) { it.executionWorkspace != null || it.phase == WorktreePhase.Failed }
        val workspace = task.executionWorkspace
        check(workspace != null && task.phase != WorktreePhase.Failed) {
            "Worktree preparation failed: ${task.failure.orEmpty()}"
        }
        return StudioIsolation(workspace, task.isSandbox)
    }

    override suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord) =
        modes.value.move(id, change)

    override suspend fun runPlanTurn(id: String, prompt: String, settings: RunSettings): RunOutcome {
        log.i { "Run the first implementation turn" }
        organisms.submit(planChat(id), prompt, settings, emptyList(), submissionId = "", onAccepted = {})?.let {
            return it
        }
        val request = StudioTurnRequest(
            id,
            prompt = prompt,
            settings = settings,
            kind = WorktreeRunKind.Coding,
            request = RequestId(Uuid.random().toString()),
        ).copy(isUserPrompt = true)
        return runHost.value.executeRun(request)
    }

    /** The studio's run host whose reserved execution is the implementation [body]. */
    private class PlanRunHost(private val host: StudioRunHost, private val body: suspend () -> RunOutcome) :
        StudioRunHost {
        override suspend fun startedRun(id: String, at: Instant) = host.startedRun(id, at)

        override suspend fun prepareRun(id: String) = host.prepareRun(id)

        override suspend fun executeRun(request: StudioTurnRequest): RunOutcome = body()

        override suspend fun finishedRun(id: String) = host.finishedRun(id)
    }
}

/**
 * Why a new [checkout] of a planning chat cannot be prepared, or null when it can. The screen offers only what is
 * available, so a refusal means the options changed meanwhile: the implementation then stops before the chat changes
 * instead of quietly moving it into another kind of checkout.
 */
internal fun checkoutRefusal(
    checkout: PlanCheckout,
    isWorktreeAvailable: Boolean,
    isSandboxAvailable: Boolean,
    isRepository: Boolean,
): String? = when {
    !isWorktreeAvailable -> "Isolated checkouts are unavailable"
    checkout == PlanCheckout.Sandbox && !isSandboxAvailable -> "Sandboxes are unavailable"
    !isRepository -> "The chat's project is not a Git repository"
    else -> null
}
