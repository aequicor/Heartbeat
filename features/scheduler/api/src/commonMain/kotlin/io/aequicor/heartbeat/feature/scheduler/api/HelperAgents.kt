package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.Serializable

/** Durable chat identity, available before the first native prompt creates its session. */
@Serializable
public data class HelperId(val value: String) {
    init {
        require(value.isNotBlank()) { "Helper identity must not be blank" }
    }
}

/** A caller-generated immutable attempt, recorded by the workflow before submission. Text is never logged. */
public data class HelperPrompt(val request: RequestId, val text: String, val isRecovery: Boolean = false) {
    override fun toString(): String = "HelperPrompt(request=$request, isRecovery=$isRecovery)"
}

/** A submission response for exactly one immutable request. Exceptions leave acceptance uncertain. */
public sealed interface HelperSubmission {
    /** The immutable request this response concerns. */
    public val request: RequestId

    /** Native acceptance is confirmed; the turn and its permissions remain owned by the profile. */
    public data class Accepted(override val request: RequestId, val session: SessionRef) : HelperSubmission

    /** The host guarantees no native submission happened or can still happen for this request. */
    public data class NotSubmitted(override val request: RequestId) : HelperSubmission
}

/** Confirmed terminal outcome, independent of the text a model happened to produce. */
public enum class HelperOutcome {
    /** The requested turn finished normally. */
    Completed,

    /** The requested turn failed. */
    Failed,

    /** Cancellation of this specific turn is confirmed. */
    Cancelled,
}

/** Result of precisely [request]; the answer is bounded for durable workflow journals and never logged. */
public data class HelperResult(
    val request: RequestId,
    val outcome: HelperOutcome,
    val answer: String,
    val turn: TurnId? = null,
) {
    init {
        require(answer.length <= SchedulerLimits.MAX_PAYLOAD) { "Helper result is too long" }
    }

    override fun toString(): String = "HelperResult(request=$request, outcome=$outcome, hasTurn=${turn != null})"
}

/** A cancellation barrier; command acceptance alone is not proof that native work has stopped. */
public sealed interface HelperCancellation {
    /** No submission happened and none can still happen for this immutable attempt. */
    public data class NotSubmitted(val request: RequestId) : HelperCancellation

    /** A terminal result of the exact requested attempt is confirmed. */
    public data class Terminal(val result: HelperResult) : HelperCancellation

    /** The attempt might still be running; its lease must retain its capacity slot. */
    public data class Unconfirmed(val request: RequestId) : HelperCancellation
}

/** Whether a lease could safely return its slot to the shared capacity pool. */
public enum class HelperReleaseResult {
    /** Native work has settled or was never submitted, and the slot is free. */
    Released,

    /** Native termination remains uncertain; the lease is closing, retains its slot, and release may be retried. */
    Unconfirmed,
}

/**
 * One slot for one helper, owned by [owner]. Hold until the helper is terminal, including uncertain submissions.
 * A released lease cannot be reused. Closing permanently prevents new creation or prompts, even if cleanup fails.
 */
public interface HelperLease {
    /** Workflow run or script action whose per-owner quota this reservation consumes. */
    public val owner: ActionId

    /** Cancels or reconciles any native work before releasing; cancellation of the wait does not abandon cleanup. */
    public suspend fun release(): HelperReleaseResult
}

/**
 * Profile service for supervised helpers. Creation and prompting are deliberately separate: callers persist
 * [HelperId] and [RequestId] in their workflow journal before sending text. Capacity is shared with scheduled
 * commands and agents; waiting is cancellable. Hosts own durable chat metadata, transcripts and permissions.
 * Approved scripts may invoke this service with Ask trust; agent-origin launches still require Command approval.
 */
public interface HelperAgents {
    /** Whether a host can supervise a helper of [parent], or a parentless helper. Check before asking approval. */
    public suspend fun canHost(parent: SessionRef?): Boolean

    /**
     * Waits for profile and owner capacity. Recovery supplies [existing] to rebind a durable helper after checking
     * its persisted owner and parent. A helper can have only one live lease; unresolved work retains its new slot.
     */
    public suspend fun acquire(owner: ActionId, parent: SessionRef? = null, existing: HelperId? = null): HelperLease

    /**
     * Creates and persists an empty helper chat, never a prompt. A parent uses its actual execution workspace;
     * [workspace] routes parentless helpers. The host enforces [trustCap] and current parent trust on every turn.
     */
    public suspend fun create(
        lease: HelperLease,
        workspace: WorkspaceRef?,
        target: EngineTarget,
        title: String,
        trustCap: TrustLevel = TrustLevel.Ask,
    ): HelperId

    /** Sends one journaled attempt using its active lease; caller cancellation does not abandon accepted work. */
    public suspend fun prompt(helper: HelperId, prompt: HelperPrompt): HelperSubmission

    /** Confirmed result of exactly [request]; null proves neither completion nor absence of native work. */
    public suspend fun result(helper: HelperId, request: RequestId): HelperResult?

    /** Cancels exactly [request], reporting a barrier rather than merely successful command dispatch. */
    public suspend fun cancel(helper: HelperId, request: RequestId): HelperCancellation

    /** Durable helper identity, including parentless helpers and helpers from previous profile lifetimes. */
    public suspend fun isHelper(session: SessionRef): Boolean

    /** Saves a terminal workflow result and publishes its finished event through the durable scheduler outbox. */
    public suspend fun finish(action: ActionId, payload: String)
}
