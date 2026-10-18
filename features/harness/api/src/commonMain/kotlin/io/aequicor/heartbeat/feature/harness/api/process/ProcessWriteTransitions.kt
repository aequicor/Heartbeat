package io.aequicor.heartbeat.feature.harness.api.process

import kotlin.time.Instant

internal fun ProcessRecord.grant(authority: ProcessAuthority, now: Instant): ProcessReduction =
    if (authority in grants) {
        ProcessReduction.Unchanged(this)
    } else {
        changed(copy(grants = grants + authority, updatedAt = now))
    }

internal fun ProcessRecord.revoke(authority: ProcessAuthority, now: Instant): ProcessReduction =
    if (authority in grants) {
        changed(copy(grants = grants - authority, updatedAt = now))
    } else {
        ProcessReduction.Unchanged(this)
    }

/**
 * Journals the intent. A key that is already confirmed is a replay and changes nothing, an open one is refused, and
 * only a write known not to have happened may be begun again.
 */
internal fun ProcessRecord.beginWrite(command: ProcessCommand.BeginWrite, now: Instant): ProcessReduction {
    val existing = write(command.key)
    return when {
        existing == null -> addWrite(command, now)

        existing.authority != command.authority || existing.target != command.target ->
            rejected(ProcessRejection.WriteKeyConflict)

        existing.phase is WritePhase.Confirmed -> ProcessReduction.Unchanged(this)

        existing.isUnresolved -> rejected(ProcessRejection.WriteUnresolved)

        command.authority !in grants -> rejected(ProcessRejection.NotAuthorized)

        else -> replaceWrite(existing.copy(phase = WritePhase.Intended, tries = existing.tries + 1, at = now), now)
    }
}

private fun ProcessRecord.addWrite(command: ProcessCommand.BeginWrite, now: Instant): ProcessReduction {
    val entry = ExternalWrite(command.key, command.authority, command.target, WritePhase.Intended, at = now)
    val room = if (writes.size < ProcessLimits.WRITES) writes else writes.dropOldestSettled()
    return when {
        command.authority !in grants -> rejected(ProcessRejection.NotAuthorized)
        room == null -> rejected(ProcessRejection.TooManyWrites)
        else -> changed(copy(writes = room + entry, updatedAt = now))
    }
}

/**
 * The journal without a settled write, or null when every write is still open and none may be forgotten. A write
 * known not to have happened goes first, because forgetting it cannot repeat anything; then the oldest confirmed one.
 * Beginning a forgotten key afterwards is a new write: the journal recognizes only the keys it still holds.
 */
private fun List<ExternalWrite>.dropOldestSettled(): List<ExternalWrite>? {
    val oldest = firstOrNull { it.phase == WritePhase.NotApplied } ?: firstOrNull { it.isSettled } ?: return null
    return this - oldest
}

internal fun ProcessRecord.confirmWrite(key: WriteKey, result: ResultRef, now: Instant): ProcessReduction {
    val existing = write(key)
    val phase = existing?.phase
    return when {
        existing == null -> rejected(ProcessRejection.UnknownWrite)

        phase is WritePhase.Confirmed ->
            if (phase.result == result) ProcessReduction.Unchanged(this) else rejected(ProcessRejection.OutcomeConflict)

        phase == WritePhase.NotApplied -> rejected(ProcessRejection.OutcomeConflict)

        else -> replaceWrite(existing.copy(phase = WritePhase.Confirmed(result), at = now), now)
    }
}

internal fun ProcessRecord.markWriteUnknown(key: WriteKey, tries: Int, now: Instant): ProcessReduction {
    val existing = write(key)
    return when {
        existing == null -> rejected(ProcessRejection.UnknownWrite)
        tries > existing.tries -> rejected(ProcessRejection.UnknownTry)
        tries < existing.tries -> ProcessReduction.Unchanged(this)
        existing.phase == WritePhase.Unknown -> ProcessReduction.Unchanged(this)
        existing.isSettled -> rejected(ProcessRejection.OutcomeConflict)
        else -> replaceWrite(existing.copy(phase = WritePhase.Unknown, at = now), now)
    }
}

internal fun ProcessRecord.writeNotApplied(key: WriteKey, tries: Int, now: Instant): ProcessReduction {
    val existing = write(key)
    return when {
        existing == null -> rejected(ProcessRejection.UnknownWrite)
        tries > existing.tries -> rejected(ProcessRejection.UnknownTry)
        tries < existing.tries -> ProcessReduction.Unchanged(this)
        existing.phase == WritePhase.NotApplied -> ProcessReduction.Unchanged(this)
        existing.phase is WritePhase.Confirmed -> rejected(ProcessRejection.OutcomeConflict)
        else -> replaceWrite(existing.copy(phase = WritePhase.NotApplied, at = now), now)
    }
}

private fun ProcessRecord.replaceWrite(entry: ExternalWrite, now: Instant): ProcessReduction =
    changed(copy(writes = writes.map { if (it.key == entry.key) entry else it }, updatedAt = now))
