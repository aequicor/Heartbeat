package io.aequicor.heartbeat.feature.harness.api.process

/**
 * Host-only commands reduced by the shared coordinator. Scripts cannot attest cleanup, exported bytes or external
 * reconciliation. The host authenticates [actor] and binds evidence to the exact coordinator revision in its CAS.
 *
 * | Phase | Command | Next | Required evidence |
 * |---|---|---|---|
 * | Active | Prepare | Preparing | explicit human transfer request, fresh id, named target |
 * | Preparing | Quiesce | Quiescing | admissions closed durably |
 * | Quiescing | Export | Exported | every executor released and admitted call drained; immutable checkpoint |
 * | Exported | Claim | Reconciling | authenticated target; atomically increment epoch |
 * | Reconciling | Activate | Active | checkpoint verified, external facts read, no unknown outcomes |
 * | Preparing/Quiescing/Exported | Cancel | Reconciling at source | rotate epoch; reconcile before resuming |
 *
 * There is deliberately no force claim, timeout takeover or "assume stopped" command. A crash leaves the same
 * phase durable. The host resumes that phase by reading facts; it never repeats a native submission blindly.
 */
public sealed interface OwnershipCommand {
    /** Independently authenticated caller, not the current owner copied from a read. */
    public val actor: ExecutorHostId

    /** Expected epoch of the command; the coordinator also CASes the complete record revision. */
    public val expected: OwnershipFence

    /** Closes admission before any cleanup. A fresh [id] distinguishes this request from late callbacks. */
    public data class Prepare(
        override val actor: ExecutorHostId,
        override val expected: OwnershipFence,
        val id: String,
        val target: ExecutorHostId,
    ) : OwnershipCommand {
        override fun toString(): String = "OwnershipCommand.Prepare(***)"
    }

    /** Starts or resumes cleanup; the old host keeps ownership but has no mutation admission. */
    public data class Quiesce(
        override val actor: ExecutorHostId,
        override val expected: OwnershipFence,
        val transfer: String,
    ) : OwnershipCommand {
        override fun toString(): String = "OwnershipCommand.Quiesce(***)"
    }

    /**
     * Records the checkpoint only after host-confirmed drainage. Nonzero [executors] or [calls] refuses export;
     * unknown status counts as outstanding. Counters are host receipts, never claims supplied by script code.
     */
    public data class Export(
        override val actor: ExecutorHostId,
        override val expected: OwnershipFence,
        val transfer: String,
        val checkpoint: OwnershipCheckpoint,
        val executors: Int,
        val calls: Int,
    ) : OwnershipCommand {
        override fun toString(): String = "OwnershipCommand.Export(***)"
    }

    /** The authenticated recipient conditionally takes the exported work; it is not active yet. */
    public data class Claim(
        override val actor: ExecutorHostId,
        override val expected: OwnershipFence,
        val transfer: String,
    ) : OwnershipCommand {
        override fun toString(): String = "OwnershipCommand.Claim(***)"
    }

    /**
     * A host receipt for a fresh read of external facts and verified checkpoint. [unresolved] includes unknown
     * writes and unknown executors. Zero is necessary but not sufficient: the host must authenticate the receipt.
     * [transfer] binds the receipt to this transfer, [checkpoint] to exactly the bytes that were reconciled;
     * cancellation before export uses null. This command grants no authority beyond the original process grants.
     */
    public data class Activate(
        override val actor: ExecutorHostId,
        override val expected: OwnershipFence,
        val transfer: String,
        val checkpoint: OwnershipCheckpoint?,
        val unresolved: Int,
    ) : OwnershipCommand {
        override fun toString(): String = "OwnershipCommand.Activate(***)"
    }

    /** Withdraws a not-yet-claimed transfer. Always rotates epoch and requires source reconciliation. */
    public data class Cancel(
        override val actor: ExecutorHostId,
        override val expected: OwnershipFence,
        val transfer: String,
    ) : OwnershipCommand {
        override fun toString(): String = "OwnershipCommand.Cancel(***)"
    }
}

/** Why the coordinator refuses a transfer step without changing its value. */
public enum class OwnershipRejection {
    /** The caller is not the owner or designated recipient. */
    Actor,

    /** The epoch, key or expected host is no longer current. */
    Fence,

    /** The command belongs to another transfer. */
    Transfer,

    /** This step cannot run in the recorded phase. */
    Phase,

    /** Cleanup or reconciliation is incomplete or unknown. */
    Unresolved,

    /** The receipt does not match the immutable export. */
    Checkpoint,

    /** A monotonic counter cannot be advanced safely. */
    Exhausted,
}

/** Pure result; only [Changed] needs a conditional durable write before its state can authorize anything. */
public sealed interface OwnershipReduction {
    /** Proposed value; the coordinator has not yet committed it. */
    public data class Changed(val record: ProcessOwnership) : OwnershipReduction

    /** Exact replay already reflected by the current state; no action should be submitted again. */
    public data class Unchanged(val record: ProcessOwnership) : OwnershipReduction

    /** The caller must re-read or reconcile, never retry a native action on this result. */
    public data class Rejected(val reason: OwnershipRejection) : OwnershipReduction
}

/**
 * Reduces one ownership command with no IO or clocks. A result is not a distributed lock: the host persists it
 * using a real conditional coordinator write. Read failure or a lost CAS response is unavailable/unknown, never
 * an active lease inferred from the local copy. Exact step replays are acknowledged only in their resulting phase.
 */
public fun ProcessOwnership.reduceOwnership(command: OwnershipCommand): OwnershipReduction {
    if (command is OwnershipCommand.Claim) return claimOwnership(command)
    if (command.expected != fence) return refuse(OwnershipRejection.Fence)
    if (command.actor != fence.host) return refuse(OwnershipRejection.Actor)
    return when (command) {
        is OwnershipCommand.Claim -> claimOwnership(command)

        is OwnershipCommand.Prepare -> prepareOwnership(command)

        is OwnershipCommand.Quiesce -> withTransfer(command.transfer) {
            when (phase) {
                OwnershipPhase.Preparing -> advance(OwnershipPhase.Quiescing)

                OwnershipPhase.Quiescing -> OwnershipReduction.Unchanged(this)

                OwnershipPhase.Active, OwnershipPhase.Exported, OwnershipPhase.Reconciling ->
                    refuse(OwnershipRejection.Phase)
            }
        }

        is OwnershipCommand.Export -> withTransfer(command.transfer) { exportOwnership(command) }

        is OwnershipCommand.Activate -> withTransfer(command.transfer) { activateOwnership(command) }

        is OwnershipCommand.Cancel -> withTransfer(command.transfer) { cancelOwnership() }
    }
}

private fun ProcessOwnership.prepareOwnership(command: OwnershipCommand.Prepare): OwnershipReduction {
    if (command.target == fence.host) return refuse(OwnershipRejection.Actor)
    val proposal = OwnershipTransfer(command.id, command.expected, command.target)
    if (phase == OwnershipPhase.Preparing && transfer == proposal) return OwnershipReduction.Unchanged(this)
    if (phase != OwnershipPhase.Active) return refuse(OwnershipRejection.Phase)
    if (transfer?.id == command.id) return refuse(OwnershipRejection.Transfer)
    return advance(OwnershipPhase.Preparing, proposal)
}

private fun ProcessOwnership.exportOwnership(command: OwnershipCommand.Export): OwnershipReduction = when {
    command.executors != 0 || command.calls != 0 -> refuse(OwnershipRejection.Unresolved)
    phase == OwnershipPhase.Exported && transfer?.checkpoint == command.checkpoint -> OwnershipReduction.Unchanged(this)
    phase != OwnershipPhase.Quiescing -> refuse(OwnershipRejection.Phase)
    else -> advance(OwnershipPhase.Exported, checkNotNull(transfer).copy(checkpoint = command.checkpoint))
}

private fun ProcessOwnership.claimOwnership(command: OwnershipCommand.Claim): OwnershipReduction {
    val pending = transfer ?: return refuse(OwnershipRejection.Transfer)
    if (command.transfer != pending.id) return refuse(OwnershipRejection.Transfer)
    if (command.actor != pending.target) return refuse(OwnershipRejection.Actor)
    if (command.expected != pending.source) return refuse(OwnershipRejection.Fence)
    val isClaimed = fence.host == pending.target && fence.epoch == pending.source.epoch + 1
    if (isClaimed && phase in listOf(OwnershipPhase.Reconciling, OwnershipPhase.Active)) {
        return OwnershipReduction.Unchanged(this)
    }
    if (phase != OwnershipPhase.Exported || fence != pending.source) return refuse(OwnershipRejection.Phase)
    return rotate(pending.target)
}

private fun ProcessOwnership.activateOwnership(command: OwnershipCommand.Activate): OwnershipReduction = when {
    command.unresolved != 0 -> refuse(OwnershipRejection.Unresolved)
    command.checkpoint != transfer?.checkpoint -> refuse(OwnershipRejection.Checkpoint)
    phase == OwnershipPhase.Active -> OwnershipReduction.Unchanged(this)
    phase != OwnershipPhase.Reconciling -> refuse(OwnershipRejection.Phase)
    else -> advance(OwnershipPhase.Active)
}

private fun ProcessOwnership.cancelOwnership(): OwnershipReduction = when {
    phase !in listOf(OwnershipPhase.Preparing, OwnershipPhase.Quiescing, OwnershipPhase.Exported) ->
        refuse(OwnershipRejection.Phase)

    else -> rotate(fence.host)
}

private fun ProcessOwnership.rotate(host: ExecutorHostId): OwnershipReduction {
    if (fence.epoch == Long.MAX_VALUE || revision == Long.MAX_VALUE) return refuse(OwnershipRejection.Exhausted)
    return OwnershipReduction.Changed(
        copy(
            fence = fence.copy(host = host, epoch = fence.epoch + 1),
            revision = revision + 1,
            phase = OwnershipPhase.Reconciling,
        ),
    )
}

private fun ProcessOwnership.advance(
    next: OwnershipPhase,
    pending: OwnershipTransfer? = transfer,
): OwnershipReduction {
    if (revision == Long.MAX_VALUE) return refuse(OwnershipRejection.Exhausted)
    return OwnershipReduction.Changed(copy(revision = revision + 1, phase = next, transfer = pending))
}

private inline fun ProcessOwnership.withTransfer(id: String, reduce: () -> OwnershipReduction): OwnershipReduction =
    if (transfer?.id != id) refuse(OwnershipRejection.Transfer) else reduce()

private fun refuse(reason: OwnershipRejection): OwnershipReduction = OwnershipReduction.Rejected(reason)
