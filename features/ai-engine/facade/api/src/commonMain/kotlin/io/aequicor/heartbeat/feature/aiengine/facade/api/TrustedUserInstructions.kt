package io.aequicor.heartbeat.feature.aiengine.facade.api

/** An original human message captured by the host input boundary, never reconstructed from a transcript. */
public data class TrustedUserInstruction(val request: RequestId, val text: String) {
    override fun toString(): String = "TrustedUserInstruction(request=$request)"
}

/**
 * Host-owned authority for the entire logical conversation, including earlier native sessions.
 * This is an in-process capability: adapters, decoded requests, tool results and transcript roles cannot produce it.
 * A missing history, conflicting request identity or exceeded bound stays [Incomplete] until a new conversation.
 */
public sealed interface TrustedUserInstructions {
    /** Every original human instruction in chronological order; an empty list grants no authority. */
    public data class Complete(val messages: List<TrustedUserInstruction>) : TrustedUserInstructions {
        override fun toString(): String = "TrustedUserInstructions.Complete(messages=${messages.size})"
    }

    /** Earlier restrictions are unavailable; later instructions cannot silently repair this history. */
    public data object Incomplete : TrustedUserInstructions
}

/** Authority budgets leave room for message boundaries within the safety review context. Never truncate to fit. */
public object TrustedUserInstructionBounds {
    /** Maximum original messages retained as a complete authority history. */
    public const val MAX_MESSAGES: Int = 128

    /** Maximum total original text, excluding host framing. */
    public const val MAX_TEXT_CHARACTERS: Int = 24_000
}
