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
import io.aequicor.heartbeat.feature.organicai.api.OrganismBounds
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.planningmode.api.PlanMode
import kotlinx.coroutines.flow.first

/** Moves an existing chat, detached from its native session, into another execution mode. */
internal interface StudioChatModes {
    /** Applies [change] to the record of chat [id]. */
    suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord)
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

    /** Fails when chat [id] cannot become the chat of an organism conceived from [goal] now. */
    suspend fun admitOrganism(id: String, goal: String, settings: RunSettings, projectId: String?): OrganismAdmission

    /** Applies [change] to the record of chat [id], detached from its native session. */
    suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord)

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
        val isImplementable = plan.isImplementable(implementation) && chat.isImplementable(implementation.mode)
        if (!isImplementable || plan == null || target == null) {
            log.w { "Plan cannot be implemented: planning=${plan?.mode ?: "none"} target=${target != null}" }
            return RunOutcome.Failed
        }
        host.validatePlanTarget(id, target)
        val settings = defaults.copy(
            modelId = implementation.settings.modelId,
            approval = implementation.settings.approval,
        )
        val prompt = implementation.prompt.ifBlank { DEFAULT_PROMPT }
        val isOrganism = implementation.mode == PlanImplementationMode.Organism
        val turn = if (isOrganism) organismGoal(prompt, plan) else prompt
        // Everything that can refuse the move is checked before the planning session is left.
        val move = move(chat, implementation.mode, turn, settings)
        val isPrepared = prepare(chat, target, implementation, settings, move)
        log.i { "Implement the plan: mode=${implementation.mode} prepared=$isPrepared" }
        if (!isPrepared || !planning.startImplementation(id, implementation.revision)) return RunOutcome.Failed
        return host.runPlanTurn(id, turn, settings)
    }

    /** How chat [chat] changes its execution mode for [mode], or null when it implements as it is. */
    private suspend fun move(
        chat: StudioChatRecord,
        mode: PlanImplementationMode,
        goal: String,
        settings: RunSettings,
    ): (StudioChatRecord.() -> StudioChatRecord)? = when (mode) {
        PlanImplementationMode.Chat -> null

        // A chat already in an isolated checkout implements there.
        PlanImplementationMode.Worktree, PlanImplementationMode.Sandbox -> if (chat.worktreeTaskId == null) {
            val isSandbox = mode == PlanImplementationMode.Sandbox
            val workspace = host.prepareWorktree(chat.id, checkNotNull(chat.projectId), isSandbox)
            val change: StudioChatRecord.() -> StudioChatRecord = {
                copy(worktreeTaskId = id, isSandbox = isSandbox, executionWorkspace = workspace)
            }
            change
        } else {
            null
        }

        PlanImplementationMode.Organism -> host.admitOrganism(chat.id, goal, settings, chat.projectId).let { it::on }
    }

    private suspend fun prepare(
        chat: StudioChatRecord,
        target: EngineTarget,
        implementation: PlanImplementation,
        settings: RunSettings,
        move: (StudioChatRecord.() -> StudioChatRecord)?,
    ): Boolean {
        val current = chat.target
        val isRouteChanged = current != null && !current.isSameRoute(target)
        val isNewContext = isRouteChanged || implementation.isFreshContext || move != null
        return when {
            chat.ref != null && !isNewContext ->
                changes(chat.configuration, implementation.settings).all { host.configure(chat.id, it) }

            chat.ref != null && host.rolloverForPlan(chat.id, settings) != RunOutcome.Completed -> false

            else -> {
                // Detached from its native session, the chat moves before its next turn opens anything.
                move?.let { host.move(chat.id, it) }
                host.adoptPlanSettings(chat.id, implementation.settings)
                true
            }
        }
    }

    private fun Plan?.isImplementable(implementation: PlanImplementation): Boolean =
        this?.mode == PlanMode.Planning && revision == implementation.revision

    /**
     * Helpers and organisms keep their turns to their owners; a checkout needs a project, and an organism isolates
     * its cells itself instead of running in the chat's checkout.
     */
    private fun StudioChatRecord.isImplementable(mode: PlanImplementationMode): Boolean {
        val isModeAllowed = when (mode) {
            PlanImplementationMode.Chat -> true
            PlanImplementationMode.Worktree, PlanImplementationMode.Sandbox -> isWorktree || projectId != null
            PlanImplementationMode.Organism -> worktreeTaskId == null
        }
        return organismId == null && helper == null && isModeAllowed
    }

    private val StudioChatRecord.isWorktree: Boolean get() = worktreeTaskId != null

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

/**
 * The goal of an organism that implements [plan]: the user's [prompt] and the plan, which its cells cannot read from
 * the chat; a plan longer than an organism goal holds is cut with a note.
 */
internal fun organismGoal(prompt: String, plan: Plan): String {
    val header = "$prompt\n\nImplement this plan (revision ${plan.revision}), developed and approved in planning " +
        "mode:\n\n<plan>\n"
    val footer = "\n</plan>"
    val budget = OrganismBounds.MAX_GOAL - header.length - footer.length
    val text = if (plan.text.length <= budget) {
        plan.text
    } else {
        plan.text.take(budget - CUT_NOTE.length).trimEnd() + CUT_NOTE
    }
    return header + text + footer
}

private const val CUT_NOTE = "\n\n[The plan is longer; its remaining sections were cut.]"
