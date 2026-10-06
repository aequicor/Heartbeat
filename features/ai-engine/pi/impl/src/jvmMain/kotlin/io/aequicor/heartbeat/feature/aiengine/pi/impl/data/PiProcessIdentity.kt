package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** A PID alone is reusable; the exact OS start instant is required to identify the original process. */
@Serializable
internal data class PiProcessIdentity(val pid: Long, val startedAt: String) {
    init {
        require(pid > 0)
        Instant.parse(startedAt)
    }

    override fun toString(): String = "PiProcessIdentity(***)"
}

/**
 * Observed members of one dedicated execution launch, captured before a native submission. This is evidence
 * for later identity checks, not proof of exit or OS containment: unobserved/detached descendants are not covered.
 * Missing evidence is represented by a null owner on the turn, never by an owner with an unknown root identity.
 */
@Serializable
internal data class PiExecutionOwner(
    val launchId: String,
    val root: PiProcessIdentity,
    val observedChildren: List<PiProcessIdentity> = emptyList(),
) {
    init {
        require(launchId.isNotBlank())
        require(root !in observedChildren)
        require(observedChildren.distinct().size == observedChildren.size)
    }

    override fun toString(): String = "PiExecutionOwner(***)"
}
