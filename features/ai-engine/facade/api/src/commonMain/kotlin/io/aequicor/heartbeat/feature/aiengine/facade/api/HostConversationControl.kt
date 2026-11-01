package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/**
 * Controls full host conversations independently of a scheduler work lease. The implementation resolves
 * [AgentToolContext.logicalSession] against its durable records and the caller's actual native segment on every
 * operation. A missing or conflicting context fails closed; model arguments never select a creator or role.
 *
 * Creation and sends are journaled before native effects. Their ids are immutable idempotency keys: repeating
 * the same request returns its recorded receipt, while different content under that key is refused. A receipt
 * does not promise native deduplication or completion. Cancellation and reconciliation concern the exact attempt.
 *
 * Only the direct creator can send to or cancel a subsession. A family root may also observe its subsessions;
 * family membership alone never grants control. Subagents use their owner/work protocol instead of this service.
 */
public interface HostConversationControl {
    /** Creates a full subsession, persisting logical identity before its optional initial message is submitted. */
    public suspend fun createSubsession(
        caller: AgentToolContext,
        creation: SubsessionCreation,
    ): HostConversationSnapshot

    /** Sends through the ordinary chat queue and its current configuration, without replaying startup settings. */
    public suspend fun send(
        caller: AgentToolContext,
        child: LogicalSessionId,
        message: HostConversationMessage,
    ): HostConversationSnapshot

    /** Observes durable identity and actual attempt evidence without opening a native session or generating text. */
    public suspend fun state(caller: AgentToolContext, child: LogicalSessionId): HostConversationSnapshot

    /** A confirmed result of precisely [request]; null never proves that it was not submitted or has ended. */
    public suspend fun result(
        caller: AgentToolContext,
        child: LogicalSessionId,
        request: RequestId,
    ): HostConversationResult?

    /** Stops precisely [request]. The full conversation remains available for later explicit user/creator messages. */
    public suspend fun cancel(
        caller: AgentToolContext,
        child: LogicalSessionId,
        request: RequestId,
    ): HostConversationCancellation
}

/** Full conversation creation; the host derives its creator, family and execution workspace from the caller. */
@Serializable
public data class SubsessionCreation(
    val request: RequestId,
    val title: String,
    val initialMessage: String? = null,
    /** Null inherits the parent's current route at creation, never at a later send. */
    val target: EngineTarget? = null,
) {
    init {
        require(title.isNotBlank() && title.length <= HostConversationLimits.MAX_TITLE)
        require(
            initialMessage == null || (
                initialMessage.isNotBlank() && initialMessage.length <= HostConversationLimits.MAX_MESSAGE
            ),
        )
    }

    override fun toString(): String =
        "SubsessionCreation(request=$request, hasInitialMessage=${initialMessage != null})"
}

/** One immutable creator message. It is agent input and must never become a trusted human instruction. */
@Serializable
public data class HostConversationMessage(val request: RequestId, val text: String) {
    init {
        require(text.isNotBlank() && text.length <= HostConversationLimits.MAX_MESSAGE)
    }

    override fun toString(): String = "HostConversationMessage(request=$request)"
}

/** Observed state of the exact attempt, separate from the conversation's interaction role. */
@Serializable
public enum class HostConversationAttemptPhase {
    /** Journaled, without a confirmed native acceptance. */
    Queued,

    /** Native submission has begun; uncertainty must be reconciled before a different attempt is admitted. */
    Submitting,

    /** Native acceptance is confirmed and the turn is active. */
    Running,

    /** The active turn waits for a specific user or environment decision. */
    Waiting,

    /** Stop intent is journaled, without a terminal native result. */
    Stopping,

    /** Native termination could not be confirmed. */
    StopUnconfirmed,

    /** An authoritative terminal outcome, including a confirmed inactive turn with unknown outcome, is recorded. */
    Finished,

    /** The host proves that this attempt was never submitted and cannot still submit. */
    NotSubmitted,

    /** The host has insufficient evidence; absence of a local job is not terminal evidence. */
    Unknown,
}

/** Evidence for one journaled request; a native session reference alone does not prove acceptance. */
@Serializable
public data class HostConversationAttempt(
    val request: RequestId,
    val phase: HostConversationAttemptPhase,
    val native: SessionRef? = null,
    val turn: TurnId? = null,
) {
    override fun toString(): String = "HostConversationAttempt(request=$request, phase=$phase)"
}

/** Current metadata, preserving the stable logical identity when the engine changes. */
@Serializable
public data class HostConversationSnapshot(
    val context: LogicalSessionContext,
    val title: String,
    val target: EngineTarget? = null,
    val attempt: HostConversationAttempt? = null,
) {
    override fun toString(): String = "HostConversationSnapshot(context=$context, attempt=${attempt ?: "none"})"
}

/** Native terminal evidence with its bounded final answer; generated text alone never determines [outcome]. */
@Serializable
public data class HostConversationResult(
    val request: RequestId,
    val outcome: TurnOutcome,
    val answer: String,
    /** False when the bounded answer omits text; the host retains history independently. */
    val isAnswerComplete: Boolean = true,
) {
    init {
        require(answer.length <= HostConversationLimits.MAX_RESULT)
    }

    override fun toString(): String = "HostConversationResult(request=$request, outcome=$outcome)"
}

/** Cancellation evidence, rather than an acknowledgement that a stop command was sent. */
@Serializable
public sealed interface HostConversationCancellation {
    /** Submission is fenced before it could start. */
    @Serializable
    public data class NotSubmitted(val request: RequestId) : HostConversationCancellation

    /** The exact attempt is authoritatively inactive. */
    @Serializable
    public data class Terminal(val result: HostConversationResult) : HostConversationCancellation

    /** It may still run; callers must retain and reconcile this exact attempt. */
    @Serializable
    public data class Unconfirmed(val request: RequestId) : HostConversationCancellation
}

/** Bounds shared by host control and its tool declarations. */
public object HostConversationLimits {
    /** Longest visible title. */
    public const val MAX_TITLE: Int = 60

    /** Longest creator message or initial task. */
    public const val MAX_MESSAGE: Int = 8_000

    /** Longest retained final answer. */
    public const val MAX_RESULT: Int = 64 * 1024
}
