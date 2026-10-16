package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/**
 * Background helper agents that another feature starts for a session and reads back itself (adaptive subagents).
 * They are scheduler actions: they share its capacity, journal, recovery and `action.<id>.finished` event, so a wake
 * on that event resumes the parent. The finished result is retained for [BackgroundAgentRequest.owner] until it
 * [acknowledge]s it, even when no wake awaits it. Available only while scheduler actions are on; tasks and results
 * are never logged.
 */
public interface BackgroundAgents {
    /** Whether a helper of [parent] can start now. */
    public suspend fun availability(parent: SessionRef): BackgroundAgentAvailability

    /**
     * Journals and starts [request]; returns once the helper chat exists. Its first prompt continues in the profile,
     * and the exact attempt's barrier and result finish the action whatever that prompt's outcome.
     */
    public suspend fun start(request: BackgroundAgentRequest): BackgroundAgentStart

    /** State of [id] retained by [owner]; another owner's or a forgotten action is [BackgroundAgentStatus.Unknown]. */
    public suspend fun status(id: ActionId, owner: String): BackgroundAgentStatus

    /** Actions [owner] still retains, running or finished, so it can reconcile its own records after a restart. */
    public suspend fun retained(owner: String): List<ActionId>

    /** Ends [owner]'s retention of [id]; a finished result nobody awaits is then forgotten. */
    public suspend fun acknowledge(id: ActionId, owner: String)
}

/** Why a background agent can or cannot start. */
public enum class BackgroundAgentAvailability {
    /** It can start. */
    Available,

    /** Scheduler actions are turned off. */
    Disabled,

    /** No chat host supervises helpers of this parent. */
    NoHost,

    /** The parent is a helper itself; helpers start no helpers. */
    HelperParent,
}

/**
 * One helper start: [owner] is the feature retaining the result, [task] the helper's first visible prompt and
 * [directive] host text for the helper only. [target] and [model] fix the helper's model, effort and review model.
 */
public data class BackgroundAgentRequest(
    val id: ActionId,
    val owner: String,
    val parent: SessionRef,
    val workspace: WorkspaceRef?,
    val target: EngineTarget,
    val title: String,
    val task: String,
    val directive: String,
    val model: HelperModelOptions = HelperModelOptions(),
    /** Exact trusted request of the parent that started it, relayed to the helper's prompts. */
    val initiator: RequestInitiator? = null,
) {
    init {
        require(owner.isNotBlank()) { "A background agent needs an owner" }
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "Invalid background agent title" }
        require(task.isNotBlank() && task.length <= MAX_TASK) { "Invalid background agent task" }
    }

    override fun toString(): String = "BackgroundAgentRequest(id=$id, owner=$owner, target=$target)"

    /** Bounds of a request. */
    public companion object {
        /** Longest chat title. */
        public const val MAX_TITLE: Int = 60

        /** Longest task. */
        public const val MAX_TASK: Int = 8_000
    }
}

/** Outcome of [BackgroundAgents.start]. */
public sealed interface BackgroundAgentStart {
    /** The helper chat exists and the action runs. */
    public data class Started(val id: ActionId) : BackgroundAgentStart

    /** Nothing started; [reason] is safe to show to the agent. */
    public data class Refused(val reason: String) : BackgroundAgentStart
}

/** State of a retained background agent. */
public sealed interface BackgroundAgentStatus {
    /** Not retained by this owner (never started, acknowledged or another owner's). */
    public data object Unknown : BackgroundAgentStatus

    /** Still running. */
    public data object Running : BackgroundAgentStatus

    /**
     * Finished. [isCompleted] when the helper's turn completed and [text] is its final message; otherwise [text]
     * says what happened (failed, cancelled, timed out, interrupted).
     */
    public data class Finished(val isCompleted: Boolean, val text: String) : BackgroundAgentStatus {
        override fun toString(): String = "Finished(isCompleted=$isCompleted, length=${text.length})"
    }
}
