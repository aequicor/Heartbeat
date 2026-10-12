package io.aequicor.heartbeat.feature.chromiumbridge.impl.domain

/** What changed between two snapshots of an ordered list of entries keyed by id. */
internal data class TranscriptChange<T>(val isReset: Boolean, val upserts: List<T>, val removed: List<String>)

/**
 * Diffs transcript snapshots for a watcher that applies upserts by id and keeps the order of first appearance.
 * The first snapshot is a reset; so is any reordering of known entries, which a merge by id could not express.
 */
internal class TranscriptDiff<T>(private val id: (T) -> String) {
    private var previous: List<T>? = null

    /** The change from the last snapshot to [current]; `null` when nothing changed. */
    fun next(current: List<T>): TranscriptChange<T>? {
        val before = previous
        previous = current
        return if (before == null) reset(current) else change(before, current)
    }

    private fun change(before: List<T>, current: List<T>): TranscriptChange<T>? {
        val currentIds = current.map(id)
        val currentSet = currentIds.toSet()
        val beforeById = before.associateBy(id)
        val kept = currentIds.filter { it in beforeById }
        val beforeKept = before.map(id).filter { it in currentSet }
        // New entries must follow every kept one, since the watcher appends them.
        val firstNew = currentIds.indexOfFirst { it !in beforeById }
        val isAppendOnly = firstNew < 0 || currentIds.drop(firstNew).none { it in beforeById }
        if (kept != beforeKept || !isAppendOnly) return reset(current)
        val upserts = current.filter { entry -> beforeById[id(entry)] != entry }
        val removed = before.map(id).filterNot { it in currentSet }
        return if (upserts.isEmpty() && removed.isEmpty()) null else TranscriptChange(false, upserts, removed)
    }

    private fun reset(current: List<T>) = TranscriptChange(isReset = true, upserts = current, removed = emptyList())
}
