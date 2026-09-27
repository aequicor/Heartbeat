package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.KoogRecord
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Profile-local observation cache shared by stored and active session handles; main dispatcher only.
 * Summaries live for the profile. Transcripts stay while a native session [pin]s them or a collector watches them;
 * beyond that only the [IDLE_HISTORIES] most recently used are kept, the rest reload from storage on demand.
 */
@SingleIn(ProfileScope::class)
@Inject
internal class KoogSessionCache(
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) {
    private val log = Log.tag("KoogCache")
    private val summaries = mutableMapOf<SessionRef, MutableStateFlow<SessionSummary>>()
    private val histories = linkedMapOf<SessionRef, KoogHistory>()
    private val pins = mutableMapOf<SessionRef, Int>()

    init {
        profile.onClose { histories.values.toList().forEach { it.close() } }
    }

    /** Reconcile durable acceptance interrupted before its in-memory event publication. */
    fun restore(record: KoogRecord): KoogSessionSnapshot {
        val previous = histories[record.summary.ref]
        if (previous != null && previous.items != record.items) {
            summaries[record.summary.ref]?.value = record.summary
            previous.restore(record.items, record.coverage)
        }
        return get(record)
    }

    fun get(record: KoogRecord): KoogSessionSnapshot {
        val ref = record.summary.ref
        val summary = summaries.getOrPut(ref) { MutableStateFlow(record.summary) }
        val history = histories.remove(ref) ?: KoogHistory(
            record.items,
            profile.coroutineScope.coroutineContext.minusKey(Job),
        ).also { it.coverage = record.coverage }
        histories[ref] = history
        trim()
        return KoogSessionSnapshot(summary, history)
    }

    /** Cached transcript of [ref], reloaded through [load] after eviction. */
    suspend fun history(ref: SessionRef, load: suspend () -> KoogRecord): KoogHistory =
        histories[ref] ?: get(load()).history

    fun pin(ref: SessionRef) {
        pins[ref] = (pins[ref] ?: 0) + 1
    }

    fun unpin(ref: SessionRef) {
        val count = (pins[ref] ?: 0) - 1
        if (count > 0) pins[ref] = count else pins.remove(ref)
        trim()
    }

    private fun trim() {
        val idle = histories.filter { (ref, history) -> ref !in pins && !history.isObserved }.keys
        val excess = idle.size - IDLE_HISTORIES
        if (excess <= 0) return
        log.d { "Evicting $excess idle transcripts" }
        idle.take(excess).forEach { histories.remove(it) }
    }

    private companion object {
        const val IDLE_HISTORIES = 8
    }
}

internal class KoogSessionSnapshot(
    private val mutableSummary: MutableStateFlow<SessionSummary>,
    val history: KoogHistory,
) {
    private val log = Log.tag("KoogSummary")
    val summary = mutableSummary.asStateFlow()

    fun update(summary: SessionSummary) {
        log.d { "Updating session metadata" }
        mutableSummary.value = summary
    }
}
