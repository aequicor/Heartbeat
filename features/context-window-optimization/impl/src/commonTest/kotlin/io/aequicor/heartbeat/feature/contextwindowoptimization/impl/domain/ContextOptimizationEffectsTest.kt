package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextLedger
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEffect
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationSettings
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.LedgerUpdate
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.updated
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ContextOptimizationEffectsTest {
    private val store = MemoryStore()
    private val ledgers = MemoryLedgers()
    private val scope = RecordingScope()
    private val effects = ContextOptimizationEffects(store, ledgers)

    @Test
    fun `load reports stored settings or their absence`() = runTest {
        effects.handle(ContextOptimizationEffect.Load, scope)
        store.value = ContextOptimizationSettings(thresholdPercent = 60)
        effects.handle(ContextOptimizationEffect.Load, scope)

        assertEquals<List<ContextOptimizationIntent>>(
            listOf(
                ContextOptimizationIntent.Internal.Loaded(null),
                ContextOptimizationIntent.Internal.Loaded(ContextOptimizationSettings(thresholdPercent = 60)),
            ),
            scope.sent,
        )
    }

    @Test
    fun `unreadable settings do not keep the ledgers from loading`() = runTest {
        store.failure = IllegalStateException("broken settings")
        ledgers.rows[One] = Ledger

        effects.handle(ContextOptimizationEffect.Load, scope)

        assertEquals<List<ContextOptimizationIntent>>(
            listOf(ContextOptimizationIntent.Internal.Loaded(null, mapOf(One to Ledger))),
            scope.sent,
        )
    }

    @Test
    fun `unreadable ledgers do not keep the settings from loading`() = runTest {
        val settings = ContextOptimizationSettings(thresholdPercent = 70)
        store.value = settings
        ledgers.failure = IllegalStateException("broken database")

        effects.handle(ContextOptimizationEffect.Load, scope)

        assertEquals<List<ContextOptimizationIntent>>(
            listOf(ContextOptimizationIntent.Internal.Loaded(settings)),
            scope.sent,
        )
    }

    @Test
    fun `a ledger save writes only its session and drops a stale one`() = runTest {
        ledgers.rows[Two] = Ledger
        val newer = Ledger.updated(LedgerUpdate(done = listOf("#2")), At)

        effects.handle(ContextOptimizationEffect.SaveLedger(One, newer, revision = 3), scope)
        effects.handle(ContextOptimizationEffect.SaveLedger(One, Ledger, revision = 2), scope)
        effects.handle(ContextOptimizationEffect.SaveLedger(Two, null, revision = 3), scope)

        assertEquals(mapOf(One to newer), ledgers.rows)
        assertEquals(listOf<SessionRef>(One, Two), ledgers.writes)
    }

    @Test
    fun `a save older than the last written revision is dropped`() = runTest {
        val newer = ContextOptimizationSettings(thresholdPercent = 90)
        effects.handle(ContextOptimizationEffect.Save(newer, revision = 2), scope)
        effects.handle(
            ContextOptimizationEffect.Save(ContextOptimizationSettings(thresholdPercent = 70), revision = 1),
            scope,
        )

        assertEquals(newer, store.value)
        assertEquals(1, store.saves)
    }

    private class MemoryStore : ContextSettingsStore {
        var value: ContextOptimizationSettings? = null
        var failure: Exception? = null
        var saves = 0

        override suspend fun load(): ContextOptimizationSettings? {
            failure?.let { throw it }
            return value
        }

        override suspend fun save(settings: ContextOptimizationSettings) {
            saves++
            value = settings
        }
    }

    private class MemoryLedgers : ContextLedgerStore {
        val rows = mutableMapOf<SessionRef, ContextLedger>()
        val writes = mutableListOf<SessionRef>()
        var failure: Exception? = null

        override suspend fun load(): Map<SessionRef, ContextLedger> {
            failure?.let { throw it }
            return rows.toMap()
        }

        override suspend fun save(session: SessionRef, ledger: ContextLedger) {
            writes += session
            rows[session] = ledger
        }

        override suspend fun delete(session: SessionRef) {
            writes += session
            rows -= session
        }
    }

    private class RecordingScope : EffectScope<ContextOptimizationIntent> {
        val sent = mutableListOf<ContextOptimizationIntent>()

        override suspend fun send(intent: ContextOptimizationIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    private companion object {
        val One = SessionRef(EngineId("pi"), SessionSourceId("s"), "one")
        val Two = SessionRef(EngineId("pi"), SessionSourceId("s"), "two")
        val At = Instant.fromEpochSeconds(10)
        val Ledger = null.updated(LedgerUpdate(goal = "Fix issues", done = listOf("#1")), At)
    }
}
