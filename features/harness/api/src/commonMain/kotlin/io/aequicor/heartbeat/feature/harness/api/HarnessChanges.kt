package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef

/** Content for a new harness; id and timestamps are supplied by the host. */
public data class HarnessDraft(
    val name: HarnessName,
    val title: String,
    val description: String = "",
    val scope: HarnessScope = HarnessScope.Attached,
    val isEnabled: Boolean = true,
    val items: List<HarnessItem> = emptyList(),
    val tools: ToolPolicySpec = ToolPolicySpec(),
) {
    override fun toString(): String = "HarnessDraft(name=$name, ***)"
}

/** Agent approvals are enforced by the caller before sending an authorized mutation to this pure machine. */
public sealed interface HarnessAuthor {
    /** A direct user edit; no agent approval is needed. */
    public data object User : HarnessAuthor

    /** An agent edit already authorized for this session. */
    public data class Agent(val session: SessionRef) : HarnessAuthor
}

/** Neither harness nor existing item names can be changed. Scope projects must already be normalized. */
public sealed interface HarnessChange {
    /** Replaces display metadata, retaining the immutable slug. */
    public data class Meta(val title: String, val description: String) : HarnessChange {
        override fun toString(): String = "HarnessChange.Meta(***)"
    }

    /** Replaces the normalized activation scope. */
    public data class Scope(val scope: HarnessScope) : HarnessChange

    /** Adds or replaces an item without renaming an existing identity. */
    public data class PutItem(val item: HarnessItem) : HarnessChange

    /** Removes one item; pinned workflow runs retain their source. */
    public data class RemoveItem(val item: ItemId) : HarnessChange

    /** Replaces declarative tool switches. */
    public data class Tools(val tools: ToolPolicySpec) : HarnessChange
}

/** Stable reasons without source code, prompt content or storage exception messages. */
public enum class HarnessRejection { Unavailable, Conflict, Busy, NotFound, Duplicate, Limit, Invalid }
