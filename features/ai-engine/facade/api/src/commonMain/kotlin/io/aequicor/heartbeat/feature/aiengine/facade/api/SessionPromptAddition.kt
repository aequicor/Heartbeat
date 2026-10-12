package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * Host context awaiting composition. A non-null [receipt] makes the entire [text] indivisible: the dispatcher
 * includes it completely or discards its receipt. Plain additions may be truncated to the remaining budget.
 */
public data class SessionPromptAddition(val text: String, val receipt: SessionPromptReceipt? = null) {
    override fun toString(): String = "SessionPromptAddition(***)"
}

/**
 * Host-owned acknowledgement, never exposed to script hooks or derived from model text. Methods must only enqueue
 * bounded host work synchronously, without IO or author callbacks. Preparation may be discarded without native
 * acceptance; this never records successful delivery. Acceptance survives toggle changes and owner closure.
 */
public interface SessionPromptReceipt {
    /**
     * The exact owner/request/turn accepted this complete addition. Null [contextRevision] means native context
     * retention cannot be proven. Enqueue before returning: the next prompt may immediately prepare on this session.
     */
    public fun accepted(context: SessionHookContext, contextRevision: String?)

    /** The addition was omitted or native submission was definitively refused; no delivery was confirmed. */
    public fun discarded(): Unit = Unit
}

/** Final composed text and only the receipts for whole blocks included in it. Owned by one prepared facade turn. */
public data class SessionPromptPreparation(val text: String, val receipts: List<SessionPromptReceipt> = emptyList()) {
    override fun toString(): String = "SessionPromptPreparation(***)"
}
