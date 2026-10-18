package io.aequicor.heartbeat.feature.harness.api.process

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Host dispatch categories; no native handle, command text or credential enters the shared register. */
@Serializable
public enum class OwnershipPermitKind {
    /** A bounded local process transaction. */
    @SerialName("mutation")
    Mutation,

    /** An owned workflow, retained until its native executors are confirmed released. */
    @SerialName("workflow")
    Workflow,

    /** A fixed native command, retained on timeout or unknown termination. */
    @SerialName("command")
    Command,

    /** A GitHub mutation, retained until its external outcome is reconciled. */
    @SerialName("github")
    GitHub,
}

/**
 * Durable admission, atomically stored with ownership BEFORE dispatch. [id] is a host-generated correlation,
 * [sequence] is never reused. An existing permit is recovery evidence, never permission to submit again.
 * Release requires host-confirmed completion or termination; neither cancellation nor time proves either.
 */
@Serializable
public data class OwnershipPermit(
    val id: String,
    val sequence: Long,
    val fence: OwnershipFence,
    val kind: OwnershipPermitKind,
) {
    init {
        require(Regex("[0-9a-f]{32}").matches(id) && sequence > 0) { "Invalid ownership permit" }
    }

    override fun toString(): String = "OwnershipPermit(sequence=$sequence, kind=$kind, ***)"
}

internal fun ProcessOwnership.admit(command: OwnershipCommand.Admit): OwnershipReduction {
    if (!accepts(command.expected)) return OwnershipReduction.Rejected(OwnershipRejection.Phase)
    val permit = OwnershipPermit(command.id, command.sequence, fence, command.kind)
    val previous = permits.firstOrNull { it.id == permit.id }
    return when {
        previous == permit -> OwnershipReduction.Unchanged(this)

        previous != null || command.sequence != nextPermit -> OwnershipReduction.Rejected(OwnershipRejection.Permit)

        permits.size >= MAX_OWNERSHIP_PERMITS -> OwnershipReduction.Rejected(OwnershipRejection.Quota)

        nextPermit == Long.MAX_VALUE || revision == Long.MAX_VALUE ->
            OwnershipReduction.Rejected(OwnershipRejection.Exhausted)

        else -> OwnershipReduction.Changed(
            copy(revision = revision + 1, nextPermit = nextPermit + 1, permits = permits + permit),
        )
    }
}

internal fun ProcessOwnership.settle(command: OwnershipCommand.Settle): OwnershipReduction {
    val permit = command.permit
    if (command.actor != permit.fence.host) return OwnershipReduction.Rejected(OwnershipRejection.Actor)
    if (command.expected != permit.fence || permit.fence.key != fence.key) {
        return OwnershipReduction.Rejected(OwnershipRejection.Fence)
    }
    val existing = permits.firstOrNull { it.id == permit.id }
    return when {
        existing == null && permit.sequence < nextPermit -> OwnershipReduction.Unchanged(this)
        existing != permit -> OwnershipReduction.Rejected(OwnershipRejection.Permit)
        revision == Long.MAX_VALUE -> OwnershipReduction.Rejected(OwnershipRejection.Exhausted)
        else -> OwnershipReduction.Changed(copy(revision = revision + 1, permits = permits - permit))
    }
}

/** Bounded durable in-flight ledger. Full capacity refuses admission without evicting a receipt. */
public const val MAX_OWNERSHIP_PERMITS: Int = 64
