package io.aequicor.heartbeat.feature.harness.impl.domain.ownership

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCommand
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipReduction
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipRejection
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.api.process.reduceOwnership

/**
 * Host-owned coordinator operations. The host fixes [actor]; scripts cannot choose it. No result authorizes a
 * native action: execution admission requires a separate durable permit covering that action's whole lifetime.
 * A reduction is committed against exactly the receipt snapshot, never silently re-reduced after a conflict.
 */
internal class OwnershipCoordinatorClient(private val backend: OwnershipBackend, private val actor: ExecutorHostId) {
    private val log = Log.tag("OwnershipCoordinator")

    @HighFrequency
    suspend fun read(key: OwnershipKey): OwnershipRead {
        log.v { "ownership read" }
        return when (val read = backend.read(key)) {
            is OwnershipRead.Unavailable -> read

            is OwnershipRead.Current -> if (read.record == null || read.record.fence.key == key) {
                read
            } else {
                OwnershipRead.Unavailable(OwnershipUnavailable.InvalidRecord)
            }
        }
    }

    /** Conditional first registration. The same key is never reset to epoch one or reassigned on read failure. */
    suspend fun create(key: OwnershipKey): OwnershipChange {
        log.v { "ownership conditional create" }
        return commit(key, null, ProcessOwnership(OwnershipFence(key, actor, 1)))
    }

    /**
     * Apply a command with evidence pinned to [snapshot]. Even an exact local replay first checks the live
     * coordinator: a cached Unchanged cannot hide another host's claim. Conflict/unknown require a fresh read.
     */
    suspend fun apply(snapshot: ProcessOwnership, command: OwnershipCommand): OwnershipChange {
        log.v { "ownership command ${command::class.simpleName.orEmpty()}" }
        if (command.actor != actor) return OwnershipChange.Rejected(OwnershipRejection.Actor)
        return when (val reduction = snapshot.reduceOwnership(command)) {
            is OwnershipReduction.Rejected -> OwnershipChange.Rejected(reduction.reason)

            is OwnershipReduction.Changed -> commit(snapshot.fence.key, snapshot, reduction.record)

            is OwnershipReduction.Unchanged -> when (val read = read(snapshot.fence.key)) {
                is OwnershipRead.Unavailable -> OwnershipChange.Unavailable(read.reason)

                is OwnershipRead.Current -> if (read.record == snapshot) {
                    OwnershipChange.Current(snapshot)
                } else {
                    OwnershipChange.Conflict
                }
            }
        }
    }

    private suspend fun commit(
        key: OwnershipKey,
        expected: ProcessOwnership?,
        next: ProcessOwnership,
    ): OwnershipChange = when (val committed = backend.compareAndSet(key, expected, next)) {
        OwnershipCommit.Applied -> OwnershipChange.Current(next, isChanged = true)
        OwnershipCommit.Conflict -> OwnershipChange.Conflict
        is OwnershipCommit.Unavailable -> OwnershipChange.Unavailable(committed.reason)
    }
}

internal sealed interface OwnershipChange {
    data class Current(val record: ProcessOwnership, val isChanged: Boolean = false) : OwnershipChange
    data object Conflict : OwnershipChange
    data class Rejected(val reason: OwnershipRejection) : OwnershipChange
    data class Unavailable(val reason: OwnershipUnavailable) : OwnershipChange
}
