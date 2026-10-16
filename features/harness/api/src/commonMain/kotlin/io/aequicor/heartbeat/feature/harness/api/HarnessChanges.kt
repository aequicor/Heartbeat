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

/**
 * Who made an [HarnessIntent.Internal.Edit]. Metadata only: agent approvals are enforced by this feature's tools
 * before they send the edit, and the pure machine never treats the author as approval.
 */
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

    /** Changes the profile's new-chat selection preference without changing existing attachments. */
    public data class DefaultForNewSessions(val isDefaultForNewSessions: Boolean) : HarnessChange

    /** Adds or replaces an item without renaming an existing identity. */
    public data class PutItem(val item: HarnessItem) : HarnessChange

    /** Removes one item; pinned workflow runs retain their source. */
    public data class RemoveItem(val item: ItemId) : HarnessChange

    /** Replaces declarative tool switches. */
    public data class Tools(val tools: ToolPolicySpec) : HarnessChange

    /**
     * Replaces metadata, every item and tool switches in one revision (configuration from a project folder or
     * bundle). Items keep their identities: an existing id keeps its name, absent items are removed.
     * The profile's default selection preference is preserved.
     */
    public data class Content(
        val title: String,
        val description: String,
        val items: List<HarnessItem>,
        val tools: ToolPolicySpec,
    ) : HarnessChange {
        override fun toString(): String = "HarnessChange.Content(items=${items.size}, ***)"
    }
}

/** Stable reasons without source code, prompt content or storage exception messages. */
public enum class HarnessRejection { Unavailable, Conflict, Busy, NotFound, Duplicate, Limit, Invalid }
