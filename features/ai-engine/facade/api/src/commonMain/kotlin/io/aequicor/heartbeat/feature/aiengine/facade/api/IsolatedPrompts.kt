package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
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
    /** Bounds queueing, creation and the whole turn; strict caller return never joins pending cleanup. */
    val timeout: Duration = 2.minutes,
    /** Legacy service answers are cut to this length; strict answers exceeding it return ResponseTooLarge. */
    val maxAnswerChars: Int = DEFAULT_ANSWER_CHARS,
    /** Opt-in strict single-request execution; an unsupported adapter refuses before any model request. */
    val constraints: IsolatedPromptConstraints? = null,
) {
    init {
        require(text.isNotBlank()) { "An isolated prompt needs text" }
        require(constraints == null || timeout.isFinite()) { "Strict isolated prompts need a finite timeout" }
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

/**
 * Strict host check: a fresh user-only context, no instructions, history, tools, hooks or discovery; exactly one
 * provider request without retries or fallback. [expectedSource] fixes every source field except its label.
 * The adapter must enforce [maxOutputTokens] on the wire or refuse before dispatch. No chat is created or saved.
 * This path has no tool dispatcher; any tool response is incomplete and is never executed.
 */
public data class IsolatedPromptConstraints(
    val expectedSource: AuthSource,
    val maxOutputTokens: Int,
    /**
     * Optional host-owned run/selection authorization, rechecked by the facade immediately before adapter dispatch
     * and after completion. It must only read current host state, never run model IO, tools or credential helpers.
     * False refuses with SourceChanged. The absolute prompt deadline includes the check; cancellation propagates.
     */
    val isCurrent: (suspend () -> Boolean)? = null,
) {
    init {
        require(expectedSource.info.label.isEmpty()) { "Isolated source context must exclude its label" }
        require(maxOutputTokens in 1..MAX_OUTPUT_TOKENS) { "Isolated output token budget is out of range" }
    }

    override fun toString(): String = "IsolatedPromptConstraints(maxOutputTokens=$maxOutputTokens)"
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

    /** The adapter cannot prove the strict execution contract without dispatching. */
    IsolationUnproven,

    /** The provider cannot enforce the requested output-token limit. */
    TokenLimitUnproven,

    /** The complete source context or host run authorization changed before dispatch or while answering. */
    SourceChanged,

    /** A strict answer exceeded the character budget; it was not silently truncated. */
    ResponseTooLarge,

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

private const val MAX_OUTPUT_TOKENS = 4096
