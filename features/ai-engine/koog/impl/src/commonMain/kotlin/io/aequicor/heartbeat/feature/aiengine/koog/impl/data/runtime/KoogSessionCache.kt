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

/** Profile-local observation cache shared by stored and active session handles; main dispatcher only. */
@SingleIn(ProfileScope::class)
@Inject
internal class KoogSessionCache(
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) {
    private val entries = mutableMapOf<SessionRef, KoogSessionSnapshot>()

    init {
        profile.onClose { entries.values.forEach { it.history.close() } }
    }

    /** Reconcile durable acceptance interrupted before its in-memory event publication. */
    fun restore(record: KoogRecord): KoogSessionSnapshot {
        val previous = entries[record.summary.ref]
        if (previous != null && previous.history.items != record.items) {
            previous.update(record.summary)
            previous.history.restore(record.items, record.coverage)
        }
        return get(record)
    }

    fun get(record: KoogRecord): KoogSessionSnapshot = entries.getOrPut(record.summary.ref) {
        KoogSessionSnapshot(
            record.summary,
            KoogHistory(record.items, profile.coroutineScope.coroutineContext.minusKey(Job)).also {
                it.coverage = record.coverage
            },
        )
    }
}

internal class KoogSessionSnapshot(initial: SessionSummary, val history: KoogHistory) {
    private val log = Log.tag("KoogSummary")
    private val mutableSummary = MutableStateFlow(initial)
    val summary = mutableSummary.asStateFlow()

    fun update(summary: SessionSummary) {
        log.d { "Updating session metadata" }
        mutableSummary.value = summary
    }
}
