package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.coroutines.flow.Flow

/**
 * Read-only native session families. Obtaining a view may start the profile transport, but never creates,
 * resumes, configures or cancels a native session. The route authorizes reads, not execution.
 * Missing capability is unsupported coverage, never an empty family with a confirmed zero activity count.
 */
public interface SessionTrees : EngineFeature {
    /** Follows authoritative descendants of [root], including nested children, independently of selection. */
    public fun observe(root: SessionRef, access: SessionTreeAccess): Flow<SessionTreeSnapshot>

    /** Reads a member's history without attaching an execution handle or changing its context. */
    public suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess): SessionHistory

    /** Optional engine-wide tree capability. */
    public companion object : EngineFeatureKey<SessionTrees>(EngineFeatureId("session.tree"), SessionTrees::class)
}

/** Explicit credential route for read-only access. No execution configuration is applied. */
public data class SessionTreeAccess(val target: EngineTarget, val workspace: WorkspaceRef? = null)

/** Native execution state. Unknown is deliberately distinct from an idle or terminal session. */
public enum class SessionActivity {
    Queued,
    Running,
    AwaitingUser,
    Idle,
    Completed,
    Cancelled,
    Failed,
    Unknown,
    ;

    /** Whether the engine positively reports outstanding work or a required user action. */
    public val isActive: Boolean get() = this == Queued || this == Running || this == AwaitingUser
}

/** A native identity and its authoritative immediate parent; names never establish relationships. */
public data class SessionTreeNode(
    val ref: SessionRef,
    val parent: SessionRef?,
    val name: String,
    val activity: SessionActivity = SessionActivity.Unknown,
)

/** Discovery coverage is separate from activity: even known children may have unknown status. */
public enum class SessionTreeCoverage { Complete, Partial, Unavailable, Unsupported }

/** A point-in-time family observation. Root is stable for the entire subscription and is never counted. */
public data class SessionTreeSnapshot(
    val root: SessionRef,
    val nodes: List<SessionTreeNode> = emptyList(),
    val coverage: SessionTreeCoverage = SessionTreeCoverage.Partial,
) {
    /** Cycle-safe depth-first family, deduplicated by the full engine/source/native identity. */
    public fun descendants(): List<SessionTreeEntry> {
        val children = nodes.distinctBy {
            it.ref
        }.filter { it.ref.engine == root.engine && it.ref.source == root.source }
            .groupBy { it.parent }
        val result = mutableListOf<SessionTreeEntry>()
        val visited = mutableSetOf(root)
        val pending = ArrayDeque<SessionTreeEntry>()
        children[root].orEmpty().asReversed().forEach { pending.add(SessionTreeEntry(it, 1)) }
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            if (!visited.add(entry.node.ref)) continue
            result.add(entry)
            children[entry.node.ref].orEmpty().asReversed().forEach {
                pending.add(SessionTreeEntry(it, entry.depth + 1))
            }
        }
        return result
    }

    /** Confirmed active descendants, excluding disconnected records and the root. */
    public val activeCount: Int get() = descendants().count { it.node.activity.isActive }

    /** False means [activeCount] is only a lower bound, including when that bound is zero. */
    public val isActivityKnown: Boolean get() = coverage == SessionTreeCoverage.Complete &&
        descendants().none { it.node.activity == SessionActivity.Unknown }
}

/** A family member with a root-relative indentation depth. */
public data class SessionTreeEntry(val node: SessionTreeNode, val depth: Int)

/** Native delegation opt-in. Read-only history and activity observation remain available when disabled. */
public val EngineSubagentsEnabled: io.aequicor.heartbeat.core.featuretoggles.FeatureToggle.Flag =
    io.aequicor.heartbeat.core.featuretoggles.FeatureToggle.Flag(
        "ai.subagents",
        "Создание нативных субагентов",
    )
