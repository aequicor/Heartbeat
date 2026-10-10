package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A PID alone is reusable; the exact OS start instant is required to identify the original process. */
@Serializable
internal data class CodexProcessIdentity(val pid: Long, val startedAt: String) {
    init {
        require(pid > 0)
        Instant.parse(startedAt)
    }

    override fun toString(): String = "CodexProcessIdentity(***)"
}

/**
 * Observed members of one dedicated execution launch, captured before a native submission. This is evidence
 * for later identity checks, not proof of exit or OS containment: unobserved/detached descendants are not covered.
 * Missing evidence is represented by a null owner on the turn, never by an owner with an unknown root identity.
 */
@Serializable
internal data class CodexExecutionOwner(
    val launchId: String,
    val root: CodexProcessIdentity,
    val observedChildren: List<CodexProcessIdentity> = emptyList(),
) {
    init {
        require(launchId.isNotBlank())
        require(root !in observedChildren)
        require(observedChildren.distinct().size == observedChildren.size)
    }

    override fun toString(): String = "CodexExecutionOwner(***)"
}
