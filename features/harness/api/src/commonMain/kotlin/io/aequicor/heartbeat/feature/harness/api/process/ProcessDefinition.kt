package io.aequicor.heartbeat.feature.harness.api.process

import io.aequicor.heartbeat.feature.harness.api.ItemId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Approved logic that owns a process. A harness revision covers all source and workflow definitions, including
 * step keys. A new revision must be explicitly accepted between stages; it never changes a saved workflow journal.
 * The host supplies this binding from the live activation rather than trusting workflow input.
 */
@Serializable
public data class ProcessDefinition(val script: ItemId, val revision: Long) {
    init {
        require(isPlainToken(script.value) && revision >= 0)
    }

    override fun toString(): String = "ProcessDefinition(***)"
}

/** Accepts a new approved logic revision without rewriting any attempts, waits or workflow step identities. */
internal fun ProcessRecord.migrateDefinition(
    command: ProcessCommand.MigrateDefinition,
    now: Instant,
): ProcessReduction = when {
    phase is ProcessPhase.Ended -> rejected(ProcessRejection.Closed)
    phase is ProcessPhase.Running -> rejected(ProcessRejection.AttemptInFlight)
    unresolvedWrites().isNotEmpty() -> rejected(ProcessRejection.WriteUnresolved)
    definition != command.expected -> rejected(ProcessRejection.DefinitionMismatch)
    definition == command.next -> ProcessReduction.Unchanged(this)
    else -> changed(copy(definition = command.next, updatedAt = now))
}
