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
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Moves an existing chat, detached from its native session, into another execution mode. */
internal interface StudioChatModes {
    /** Applies [change] to the record of chat [id]. */
    suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord)
}

/** The isolated checkout prepared for a chat: [isSandbox] is what the worktree journal made of the request. */
internal data class StudioIsolation(val workspace: WorkspaceRef, val isSandbox: Boolean)

/**
 * The parts of the studio repository that implementing a plan uses. An implementation runs inside [reserve], which
 * holds the chat like a user's turn; the operations that change the chat are valid only there.
 */
internal interface StudioPlanHost {
    /** The chat with its current confirmed configuration. */
    suspend fun planChat(id: String): StudioChatRecord

    /**
     * Fails when [target] is not an enabled route the chat can use or, when the chat moves into a new checkout
     * ([isCheckoutNeeded]), isolated checkouts are unavailable: worktree mode is off or the platform has no projects.
     */
    suspend fun validatePlanTarget(id: String, target: EngineTarget, isCheckoutNeeded: Boolean)

    /** Fails when chat [id] cannot become the chat of an organism conceived from [goal] now. */
    suspend fun admitOrganism(
        id: String,
        goal: String,
        settings: RunSettings,
        projectId: String?,
        isWorktree: Boolean,
    ): OrganismAdmission

    /**
     * Reserves chat [id] as one run of the studio with [settings] and runs [body] under the reservation: the chat is
     * busy for other turns, scheduled wakes and implementations, and a stop of the chat is recorded for
     * [isStopRequested]. Fails when the chat is busy.
     */
    suspend fun reserve(id: String, settings: RunSettings, body: suspend () -> RunOutcome): RunOutcome

    /** Inside [reserve]: the user asked to stop the chat. */
    suspend fun isStopRequested(id: String): Boolean

    /** Inside [reserve]: changes one setting of the chat's current native session; false when it was not applied. */
    suspend fun configure(sessionId: String, change: StudioSettingChange): Boolean

    /** Inside [reserve]: continues the chat in a new native session; the old session is closed. */
    suspend fun rolloverForPlan(id: String, settings: RunSettings): RunOutcome

    /** Inside [reserve]: makes [settings] the chat's configuration, so its next native session opens with them. */
    suspend fun adoptPlanSettings(id: String, settings: StudioSessionSettings)

    /** Inside [reserve]: prepares the isolated checkout of [project] the record of chat [id] already names. */
    suspend fun prepareWorktree(id: String, project: String, isSandbox: Boolean): StudioIsolation

    /** Inside [reserve]: applies [change] to the record of chat [id], detached from its native session. */
    suspend fun move(id: String, change: StudioChatRecord.() -> StudioChatRecord)

    /** Inside [reserve]: runs [prompt] as the user's turn of the chat; an organism chat conceives its organism. */
    suspend fun runPlanTurn(id: String, prompt: String, settings: RunSettings): RunOutcome
}

/**
 * Implements the plan of a planning chat in the same chat. The chosen route of the same engine and connection is
 * applied to the current session; another engine or connection, a fresh context or a move into another execution
 * mode continues the chat in a new native session that opens on the chosen route, with the essence of the planning
 * conversation.
 *
 * The whole implementation runs under one reservation of the chat, like a user's turn. Everything that can refuse
 * runs first, before the chat changes: the plan and route, the availability of the mode. The plan leaves
 * planning atomically only after the reversible steps (settings of the current session, a rollover that keeps the
 * chat a planning chat); the chat moves into its checkout or becomes an organism chat only after that. A refused
 * step undoes the settings it applied; a failed checkout leaves the chat marked failed with its recoverable
 * identity; a failed conception makes the chat an ordinary chat again. A stop before the plan leaves planning cancels
 * the implementation and keeps planning; a later stop keeps the moved chat and skips its first turn.
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
    ): RunOutcome = try {
        val chosen = settings.copy(
            modelId = implementation.settings.modelId,
            effort = implementation.settings.reasoningEffort?.toRunEffort() ?: settings.effort,
            approval = implementation.settings.approval,
        )
        // The chat is reserved first, so a stop, a turn or a wake never finds it idle while it is prepared.
        host.reserve(sessionId, chosen) { implement(sessionId, implementation, chosen) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "The plan was not implemented: mode=${implementation.mode}" }
        RunOutcome.Failed
    }

    private suspend fun implement(id: String, implementation: PlanImplementation, settings: RunSettings): RunOutcome {
        val plan = planning.plans.first()[id]
        val chat = host.planChat(id)
        val target = studioModelTarget(implementation.settings.modelId)
        val isImplementable = plan.isImplementable(implementation) && chat.isImplementable(implementation.mode)
        if (!isImplementable || plan == null || target == null) {
            log.w { "Plan cannot be implemented: planning=${plan?.mode ?: "none"} target=${target != null}" }
            return RunOutcome.Failed
        }
        val prompt = implementation.prompt.ifBlank { DEFAULT_PROMPT }
        val isOrganism = implementation.mode == PlanImplementationMode.Organism
        val turn = if (isOrganism) organismGoal(prompt, plan) else prompt
        // Everything that can refuse the move is checked before the chat changes.
        val isCheckoutNeeded = implementation.mode in CHECKOUTS && chat.worktreeTaskId == null
        host.validatePlanTarget(id, target, isCheckoutNeeded)
        val admission = if (isOrganism) {
            host.admitOrganism(id, turn, settings, chat.projectId, isWorktree = chat.worktreeTaskId != null)
        } else {
            null
        }
        val step = PlanStep(chat, target, implementation, settings, admission)
        log.i { "Implement the plan: mode=${implementation.mode} moves=${step.isMoving}" }
        return prepare(step) ?: turn(step, turn)
    }

    /** Prepares the chat under its reservation; null when its first implementation turn runs. */
    private suspend fun prepare(step: PlanStep): RunOutcome? {
        val chat = step.chat
        return when {
            chat.ref != null && !step.isNewContext -> configure(step)

            chat.ref != null && host.rolloverForPlan(chat.id, step.settings) != RunOutcome.Completed ->
                RunOutcome.Failed.also { log.w { "The planning chat could not continue in a new native session" } }

            else -> start(chat.id, step.implementation) ?: move(step)
        }
    }

    /** Detached from its native session, the chat moves before its next turn opens anything. */
    private suspend fun move(step: PlanStep): RunOutcome? {
        val chat = step.chat
        val mode = step.implementation.mode
        val isMoved = when (mode) {
            PlanImplementationMode.Chat -> true

            PlanImplementationMode.Worktree, PlanImplementationMode.Sandbox -> !step.isMoving || isolate(chat, mode)

            PlanImplementationMode.Organism -> {
                host.move(chat.id) { checkNotNull(step.admission).on(this) }
                true
            }
        }
        if (!isMoved) return RunOutcome.Failed
        host.adoptPlanSettings(chat.id, step.implementation.settings)
        return if (host.isStopRequested(chat.id)) stopped(step) else null
    }

    /** A stop after the plan left planning keeps the moved chat; an organism that was not conceived is left. */
    private suspend fun stopped(step: PlanStep): RunOutcome {
        log.i { "Implementation stopped before its first turn: mode=${step.implementation.mode}" }
        if (step.implementation.mode == PlanImplementationMode.Organism) leaveOrganism(step.chat.id)
        return RunOutcome.Stopped
    }

    /** The settings of the current session; refused, stopped or not started, the applied ones are undone. */
    private suspend fun configure(step: PlanStep): RunOutcome? {
        val id = step.chat.id
        val current = step.chat.configuration
        val undo = mutableListOf<StudioSettingChange>()
        for (change in changes(current, step.implementation.settings)) {
            if (!apply(id, change)) {
                log.w { "Implementation setting not applied: ${change.kind}; undo applied=${undo.size}" }
                undo(id, undo)
                return RunOutcome.Failed
            }
            change.undo(current)?.let { undo += it }
        }
        val outcome = start(id, step.implementation)
        if (outcome != null) undo(id, undo)
        return outcome
    }

    /** Takes the plan out of planning; the outcome when it stays there. */
    private suspend fun start(id: String, implementation: PlanImplementation): RunOutcome? = when {
        host.isStopRequested(id) -> RunOutcome.Stopped.also { log.i { "Implementation stopped while preparing" } }

        !planning.startImplementation(id, implementation.revision) ->
            RunOutcome.Failed.also { log.w { "The plan did not start its implementation" } }

        else -> null
    }

    /**
     * Moves chat [chat] into a new checkout like a new isolated chat: the record names its task before the journal
     * prepares it, so a failed or interrupted preparation keeps a recoverable identity instead of an orphan copy.
     */
    private suspend fun isolate(chat: StudioChatRecord, mode: PlanImplementationMode): Boolean {
        val isSandbox = mode == PlanImplementationMode.Sandbox
        host.move(chat.id) { copy(worktreeTaskId = id, isSandbox = isSandbox, executionWorkspace = null) }
        val isolation = try {
            host.prepareWorktree(chat.id, checkNotNull(chat.projectId), isSandbox)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Checkout preparation failed; retain its recoverable identity: mode=$mode" }
            host.move(chat.id) { copy(hasFailed = true) }
            return false
        }
        log.i { "Move the planning chat into its checkout: mode=$mode sandbox=${isolation.isSandbox}" }
        host.move(chat.id) {
            copy(isSandbox = isolation.isSandbox, executionWorkspace = isolation.workspace, hasFailed = false)
        }
        return true
    }

    /** The first implementation turn; an organism that was not conceived leaves the chat an ordinary chat again. */
    private suspend fun turn(step: PlanStep, prompt: String): RunOutcome {
        val isOrganism = step.implementation.mode == PlanImplementationMode.Organism
        val outcome = try {
            host.runPlanTurn(step.chat.id, prompt, step.settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isOrganism) leaveOrganism(step.chat.id)
            throw e
        }
        if (isOrganism && outcome != RunOutcome.Completed) leaveOrganism(step.chat.id)
        return outcome
    }

    private suspend fun leaveOrganism(id: String) {
        log.w { "The implementing organism was not conceived; the chat is an ordinary chat again" }
        host.move(id) { copy(organismId = null, organismHarnesses = emptySet()) }
    }

    private suspend fun apply(id: String, change: StudioSettingChange): Boolean = try {
        host.configure(id, change)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Implementation setting failed: ${change.kind}" }
        false
    }

    private suspend fun undo(id: String, changes: List<StudioSettingChange>) {
        changes.asReversed().forEach { change ->
            if (!apply(id, change)) log.w { "Could not restore the planning setting: ${change.kind}" }
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

            PlanImplementationMode.Worktree, PlanImplementationMode.Sandbox ->
                worktreeTaskId != null || projectId != null

            PlanImplementationMode.Organism -> worktreeTaskId == null
        }
        return organismId == null && helper == null && isModeAllowed
    }

    private fun changes(current: StudioSessionSettings?, chosen: StudioSessionSettings): List<StudioSettingChange> =
        listOfNotNull(
            StudioSettingChange.Model(chosen.modelId).takeIf { current?.modelId != chosen.modelId },
            StudioSettingChange.Effort(
                chosen.reasoningEffort,
            ).takeIf { current?.reasoningEffort != chosen.reasoningEffort },
            StudioSettingChange.Approval(chosen.approval).takeIf { current?.approval != chosen.approval },
        )

    /** The change that restores [current], or null when nothing known can be restored. */
    private fun StudioSettingChange.undo(current: StudioSessionSettings?): StudioSettingChange? = current?.let {
        when (this) {
            is StudioSettingChange.Model -> StudioSettingChange.Model(it.modelId)
            is StudioSettingChange.Effort -> StudioSettingChange.Effort(it.reasoningEffort)
            is StudioSettingChange.Approval -> StudioSettingChange.Approval(it.approval)
        }
    }

    private val StudioSettingChange.kind: String get() = this::class.simpleName.orEmpty()

    /** One implementation as it was admitted. */
    private class PlanStep(
        val chat: StudioChatRecord,
        target: EngineTarget,
        val implementation: PlanImplementation,
        val settings: RunSettings,
        val admission: OrganismAdmission?,
    ) {
        /** The chat moves into another execution mode: a new checkout or an organism. */
        val isMoving: Boolean = when (implementation.mode) {
            PlanImplementationMode.Chat -> false
            PlanImplementationMode.Worktree, PlanImplementationMode.Sandbox -> chat.worktreeTaskId == null
            PlanImplementationMode.Organism -> true
        }

        /** The implementation needs a new native session. */
        val isNewContext: Boolean = run {
            val current = chat.target
            val isRouteChanged = current != null &&
                (current.engine != target.engine || current.binding != target.binding)
            isRouteChanged || implementation.isFreshContext || isMoving
        }
    }

    private companion object {
        const val DEFAULT_PROMPT = "Implement the approved plan."
        val CHECKOUTS = setOf(PlanImplementationMode.Worktree, PlanImplementationMode.Sandbox)
    }
}

/** The run effort of a session's reasoning effort level, or null when the studio has no such level. */
private fun String.toRunEffort(): ReasoningEffort? = if (equals(VERY_HIGH_EFFORT, ignoreCase = true)) {
    ReasoningEffort.VeryHigh
} else {
    ReasoningEffort.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
}

/**
 * The goal of an organism that implements [plan]: the user's [prompt] and the plan, which its cells cannot read from
 * the chat. A prompt longer than [MAX_GOAL_PROMPT] keeps its beginning, and a plan longer than the rest of the goal
 * is cut, each with a note, so the goal never exceeds [OrganismBounds.MAX_GOAL].
 */
internal fun organismGoal(prompt: String, plan: Plan): String {
    val header = "${cutGoalPart(prompt, MAX_GOAL_PROMPT, PROMPT_CUT_NOTE)}\n\nImplement this plan " +
        "(revision ${plan.revision}), developed and approved in planning mode:\n\n<plan>\n"
    val footer = "\n</plan>"
    return header + cutGoalPart(plan.text, OrganismBounds.MAX_GOAL - header.length - footer.length, CUT_NOTE) + footer
}

/** [text] within [max] characters: its beginning and [note], never splitting a surrogate pair. */
private fun cutGoalPart(text: String, max: Int, note: String): String {
    if (text.length <= max) return text
    var end = (max - note.length).coerceAtLeast(0)
    if (end in 1..text.length && text[end - 1].isHighSurrogate()) end--
    return text.take(end).trimEnd() + note
}

/** The longest user prompt an organism goal keeps, leaving most of the goal to the plan. */
internal const val MAX_GOAL_PROMPT = OrganismBounds.MAX_GOAL / 4

private const val VERY_HIGH_EFFORT = "xhigh"
private const val CUT_NOTE = "\n\n[The plan is longer; its remaining sections were cut.]"
private const val PROMPT_CUT_NOTE = "\n\n[The request is longer; its remaining text was cut.]"
