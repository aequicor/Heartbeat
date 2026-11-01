package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** Stable host conversation or executor identity, independent of native segments and accepted turns. */
@Serializable
public data class LogicalSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "A logical session needs an identity" }
    }
}

/** Interaction role; background execution and orchestration do not change it. */
@Serializable
public enum class SessionKind {
    /** User-owned conversation at sidebar level one. */
    Session,

    /** Agent-created conversation with the same user capabilities, at sidebar level two. */
    Subsession,

    /** Executor controlled through one owner conversation, absent from the sidebar. */
    Subagent,
}

/**
 * Host-persisted logical identity. [root] groups the two-level sidebar; [creator] retains the direct creator
 * even when that creator is itself a subsession. [owner] exists only for a subagent and identifies the sole
 * conversation allowed to conduct its working dialogue. Native execution ancestry is a separate relation.
 *
 * Construction validates local invariants only. The host also checks persisted profile membership, existing
 * parents and absence of cycles before accepting a relationship. Titles and native refs never prove ownership.
 */
@Serializable
public data class LogicalSessionContext(
    val id: LogicalSessionId,
    val kind: SessionKind,
    val root: LogicalSessionId = id,
    val creator: LogicalSessionId? = null,
    val owner: LogicalSessionId? = null,
) {
    init {
        when (kind) {
            SessionKind.Session -> require(root == id && creator == null && owner == null)
            SessionKind.Subsession -> require(root != id && creator != null && creator != id && owner == null)
            SessionKind.Subagent -> require(root != id && creator != id && owner != null && owner != id)
        }
    }

    /** Full conversations can create another full conversation without introducing sidebar level three. */
    public fun subsession(child: LogicalSessionId): LogicalSessionContext {
        require(kind != SessionKind.Subagent) { "A subagent cannot create an interactive conversation" }
        return LogicalSessionContext(child, SessionKind.Subsession, root, creator = id)
    }

    /** Full conversations create executors whose owner remains explicit across native segment changes. */
    public fun subagent(child: LogicalSessionId): LogicalSessionContext {
        require(kind != SessionKind.Subagent) { "Native delegation must retain the original conversation owner" }
        return LogicalSessionContext(child, SessionKind.Subagent, root, creator = id, owner = id)
    }

    /** Direct chat control; family membership alone grants neither sibling nor ancestor control. */
    public fun isControlledBy(chat: LogicalSessionId): Boolean = when (kind) {
        SessionKind.Session -> false
        SessionKind.Subsession -> creator == chat
        SessionKind.Subagent -> owner == chat
    }

    /** User input and configuration are available in both full conversation roles. */
    public val isHumanInteractive: Boolean get() = kind != SessionKind.Subagent
}
