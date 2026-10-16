package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One-shot host prompts answered in a fresh service session on the given model: no workspace, hosted tools, session
 * hooks of features or inherited harnesses; native tools are switched off before submission and any permission
 * request ends the turn unanswered. For host-side classification and synthesis (routing, summaries), never for work
 * with side effects. Prompts and answers are never logged. Calls share a small profile capacity and queue.
 */
public fun interface IsolatedPrompts {
    /** Answers [prompt] once; failures are values, cancellation propagates and stops the service turn. */
    public suspend fun ask(prompt: IsolatedPrompt): IsolatedAnswer
}

/** A host-authored prompt for [target]; untrusted data inside [text] must be fenced by the caller. */
public data class IsolatedPrompt(
    val target: EngineTarget,
    val text: String,
    /** Bounds session creation and the whole turn. */
    val timeout: Duration = 2.minutes,
    /** The final answer is cut to this length. */
    val maxAnswerChars: Int = DEFAULT_ANSWER_CHARS,
) {
    init {
        require(text.isNotBlank()) { "An isolated prompt needs text" }
        require(timeout.isPositive()) { "An isolated prompt needs a positive timeout" }
        require(maxAnswerChars in 1..MAX_ANSWER_CHARS) { "Isolated answer budget is out of range" }
    }

    override fun toString(): String = "IsolatedPrompt(target=$target, length=${text.length}, timeout=$timeout)"

    /** Answer budgets. */
    public companion object {
        /** Enough for a structured JSON answer. */
        public const val DEFAULT_ANSWER_CHARS: Int = 16 * 1024

        /** Upper bound of one answer. */
        public const val MAX_ANSWER_CHARS: Int = 128 * 1024
    }
}

/** Outcome of one isolated prompt. */
public sealed interface IsolatedAnswer {
    /** The completed turn's final answer, possibly empty. */
    public data class Answered(val text: String) : IsolatedAnswer {
        override fun toString(): String = "IsolatedAnswer.Answered(length=${text.length})"
    }

    /** No trustworthy answer; [reason] is safe to log. */
    public data class Unavailable(val reason: IsolatedPromptFailure) : IsolatedAnswer
}

/** Why an isolated prompt has no answer. */
public enum class IsolatedPromptFailure {
    /** The engine cannot open a separate tool-less session (or its tool catalog is unknown). */
    Unsupported,

    /** The capacity stayed full for the queue wait. */
    Busy,

    /** The profile is closing. */
    Closing,

    /** Session creation or the turn exceeded the timeout. */
    Timeout,

    /** The turn did not complete normally (failed, cancelled, asked for permission). */
    Incomplete,

    /** The provider or runtime failed. */
    Failed,
}
