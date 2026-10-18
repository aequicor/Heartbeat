package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementation
import io.aequicor.heartbeat.feature.aistudio.api.PlanImplementationMode
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import kotlinx.coroutines.flow.first

/** Moves an existing chat into an isolated checkout prepared for it. */
internal interface StudioChatIsolation {
    /** Records [workspace] of the checkout task named after chat [id] as the chat's execution workspace. */
    suspend fun isolate(id: String, workspace: WorkspaceRef, isSandbox: Boolean)
}

/** The parts of the studio repository that implementing a plan uses. */
internal interface StudioPlanHost {
    /** The chat with its current confirmed configuration. */
    suspend fun planChat(id: String): StudioChatRecord

    /** Fails when [target] is not an enabled route the chat can use. */
    suspend fun validatePlanTarget(id: String, target: EngineTarget)

    /** Changes one setting of the chat's current native session; false when it was not applied. */
    suspend fun configure(sessionId: String, change: StudioSettingChange): Boolean

    /** Continues the chat in a new native session as a reserved run; the old session is closed. */
    suspend fun rolloverForPlan(id: String, settings: RunSettings): RunOutcome

    /** Makes [settings] the chat's configuration, so its next native session opens with them. */
    suspend fun adoptPlanSettings(id: String, settings: StudioSessionSettings)

    /** Prepares an isolated checkout of [project] for chat [id] and returns its workspace. */
    suspend fun prepareWorktree(id: String, project: String, isSandbox: Boolean): WorkspaceRef

    /** Moves chat [id], detached from its native session, into its prepared checkout [workspace]. */
    suspend fun isolate(id: String, workspace: WorkspaceRef, isSandbox: Boolean)

    /** Runs [prompt] as the user's turn of the chat. */
    suspend fun runPlanTurn(id: String, prompt: String, settings: RunSettings): RunOutcome
}

/**
 * Implements the plan of a planning chat in the same chat. The chosen route of the same engine and connection is
 * applied to the current session; another engine or connection, or a fresh context, continues the chat in a new
 * native session that opens on the chosen route, with the essence of the planning conversation. The plan leaves
 * planning only after the chat is ready for it, and stays attached to the context of the implementation turns.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StudioPlanImplementer(private val planning: StudioPlanning, private val host: StudioPlanHost) :
    StudioPlanRuntime {
    private val log = Log.tag("StudioPlanImplementer")

    override suspend fun implementPlan(
        sessionId: String,
        implementation: PlanImplementation,
        settings: RunSettings,
    ): RunOutcome = implement(sessionId, implementation, settings)

    private suspend fun implement(id: String, implementation: PlanImplementation, defaults: RunSettings): RunOutcome {
        val plan = planning.plans.first()[id]
        val chat = host.planChat(id)
        val target = studioModelTarget(implementation.settings.modelId)
        // A chat already in an isolated checkout implements there.
        val isolation = implementation.mode.takeIf { it != PlanImplementationMode.Chat && chat.worktreeTaskId == null }
        val isImplementable = plan.isImplementable(implementation) && chat.isImplementable(isolation) && target != null
        if (!isImplementable || target == null) {
            log.w { "Plan cannot be implemented: planning=${plan?.mode ?: "none"} target=${target != null}" }
            return RunOutcome.Failed
        }
        host.validatePlanTarget(id, target)
        val isSandbox = isolation == PlanImplementationMode.Sandbox
        val workspace = isolation?.let { host.prepareWorktree(id, checkNotNull(chat.projectId), isSandbox) }
        val settings = defaults.copy(
            modelId = implementation.settings.modelId,
            approval = implementation.settings.approval,
        )
        val isPrepared = prepare(chat, target, implementation, settings) {
            workspace?.let { host.isolate(id, it, isSandbox) }
        }
        log.i { "Implement the plan: prepared=$isPrepared route changed=${chat.target?.isSameRoute(target) == false}" }
        if (!isPrepared || !planning.startImplementation(id, implementation.revision)) return RunOutcome.Failed
        return host.runPlanTurn(id, implementation.prompt.ifBlank { DEFAULT_PROMPT }, settings)
    }

    private suspend fun prepare(
        chat: StudioChatRecord,
        target: EngineTarget,
        implementation: PlanImplementation,
        settings: RunSettings,
        detached: suspend () -> Unit,
    ): Boolean {
        val current = chat.target
        val isRouteChanged = current != null && !current.isSameRoute(target)
        val isMoved = implementation.mode != PlanImplementationMode.Chat && chat.worktreeTaskId == null
        val isNewContext = isRouteChanged || implementation.isFreshContext || isMoved
        return when {
            chat.ref != null && !isNewContext ->
                changes(chat.configuration, implementation.settings).all { host.configure(chat.id, it) }

            chat.ref != null && host.rolloverForPlan(chat.id, settings) != RunOutcome.Completed -> false

            else -> {
                // Detached from its native session, the chat moves into its checkout before its next session opens.
                detached()
                host.adoptPlanSettings(chat.id, implementation.settings)
                true
            }
        }
    }

    private fun Plan?.isImplementable(implementation: PlanImplementation): Boolean =
        this?.mode == PlanMode.Planning && revision == implementation.revision

    /** Helpers and organisms keep their turns to their owners; moving into a checkout needs a project. */
    private fun StudioChatRecord.isImplementable(isolation: PlanImplementationMode?): Boolean =
        organismId == null && helper == null && (isolation == null || projectId != null)

    private fun changes(current: StudioSessionSettings?, chosen: StudioSessionSettings): List<StudioSettingChange> =
        listOfNotNull(
            StudioSettingChange.Model(chosen.modelId).takeIf { current?.modelId != chosen.modelId },
            StudioSettingChange.Effort(
                chosen.reasoningEffort,
            ).takeIf { current?.reasoningEffort != chosen.reasoningEffort },
            StudioSettingChange.Approval(chosen.approval).takeIf { current?.approval != chosen.approval },
        )

    private fun EngineTarget.isSameRoute(other: EngineTarget): Boolean =
        engine == other.engine && binding == other.binding

    private companion object {
        const val DEFAULT_PROMPT = "Implement the approved plan."
    }
}
