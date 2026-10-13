package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent.Internal
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent.Public
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState.Ready
import kotlin.test.Test
import kotlin.time.Instant

class ContextOptimizationMachineTest {
    private val spec = ContextOptimizationMachineSpec

    @Test
    fun `start loads stored settings`() {
        spec.assertTransition(
            from = ContextOptimizationState.Idle,
            intent = Public.Start,
            to = ContextOptimizationState.Loading,
            effects = listOf(ContextOptimizationEffect.Load),
        )
    }

    @Test
    fun `loaded settings or defaults make the machine ready`() {
        val stored = ContextOptimizationSettings(thresholdPercent = 70)
        spec.assertTransition(ContextOptimizationState.Loading, Internal.Loaded(stored), Ready(stored))
        spec.assertTransition(ContextOptimizationState.Loading, Internal.Loaded(null), Ready())
        spec.assertTransition(ContextOptimizationState.Loading, Internal.LoadFailed, Ready())
    }

    @Test
    fun `commands before loading are ignored`() {
        spec.assertIgnored(ContextOptimizationState.Idle, Public.Compact(Session, CompactionOrigin.User))
        spec.assertIgnored(ContextOptimizationState.Loading, Public.UpdateSettings(ContextOptimizationSettings()))
    }

    @Test
    fun `changed settings are saved with a new revision`() {
        val settings = ContextOptimizationSettings(strategy = CompactionStrategy.Rollover)
        spec.assertTransition(
            from = Ready(),
            intent = Public.UpdateSettings(settings),
            to = Ready(settings, revision = 1),
            effects = listOf(ContextOptimizationEffect.Save(settings, 1)),
        )
        spec.assertIgnored(Ready(), Public.UpdateSettings(ContextOptimizationSettings()))
    }

    @Test
    fun `a compaction request follows the strategy unless a method is given`() {
        spec.assertTransition(
            from = Ready(),
            intent = Public.Compact(Session, CompactionOrigin.Agent, "keep tests", sourceRequest = RequestId("native")),
            to = Ready(
                pending = mapOf(
                    Session to PendingCompaction(
                        CompactionMethod.Native,
                        CompactionOrigin.Agent,
                        "keep tests",
                        RequestId("native"),
                    ),
                ),
            ),
        )
        val rollover = ContextOptimizationSettings(strategy = CompactionStrategy.Rollover)
        spec.assertTransition(
            from = Ready(rollover),
            intent = Public.Compact(Session, CompactionOrigin.Feature),
            to = Ready(
                rollover,
                pending = mapOf(Session to PendingCompaction(CompactionMethod.Rollover, CompactionOrigin.Feature)),
            ),
        )
        spec.assertIgnored(Ready(running = mapOf(Session to Pending)), Public.Compact(Session, CompactionOrigin.User))
    }

    @Test
    fun `a settled turn above the threshold makes an automatic compaction pending`() {
        spec.assertTransition(
            from = Ready(),
            intent = Public.TurnSettled(Session, usage(85), Both),
            to = Ready(
                pending = mapOf(Session to PendingCompaction(CompactionMethod.Native, CompactionOrigin.Auto)),
                marks = mapOf(Session to CompactionMark(attempts = 1)),
                usage = mapOf(Session to usage(85)),
            ),
        )
        spec.assertTransition(
            from = Ready(pending = mapOf(Session to Pending)),
            intent = Public.TurnSettled(Session, usage(90), Both),
            to = Ready(pending = mapOf(Session to Pending), usage = mapOf(Session to usage(90))),
        )
        spec.assertTransition(
            from = Ready(),
            intent = Public.TurnSettled(Session, usage(90), Both, isAutomatic = false),
            to = Ready(usage = mapOf(Session to usage(90))),
        )
    }

    @Test
    fun `a claimed compaction runs until it finishes`() {
        spec.assertTransition(
            from = Ready(pending = mapOf(Session to Pending)),
            intent = Public.Claim(Session),
            to = Ready(running = mapOf(Session to Pending)),
        )
        spec.assertIgnored(Ready(), Public.Claim(Session))
        spec.assertTransition(
            from = Ready(running = mapOf(Session to Pending)),
            intent = Public.Finished(Session, CompactionResult.Compacted),
            to = Ready(),
        )
    }

    @Test
    fun `a rollover moves the pending request and mark to the new session`() {
        val mark = CompactionMark(attempts = 2)
        spec.assertTransition(
            from = Ready(pending = mapOf(Session to Pending), marks = mapOf(Session to mark)),
            intent = Public.RolledOver(Session, Next),
            to = Ready(pending = mapOf(Next to Pending), marks = mapOf(Next to mark)),
        )
        spec.assertTransition(
            from = Ready(pending = mapOf(Session to Pending), marks = mapOf(Session to mark)),
            intent = Public.SessionClosed(Session),
            to = Ready(),
        )
    }

    @Test
    fun `a checkpoint updates and saves the ledger which follows a rollover`() {
        val at = Instant.fromEpochSeconds(10)
        val ledger = null.updated(LedgerUpdate(goal = "Fix issues", done = listOf("#1")), at)
        spec.assertTransition(
            from = Ready(),
            intent = Public.UpdateLedger(Session, LedgerUpdate(goal = "Fix issues", done = listOf("#1")), at),
            to = Ready(ledgers = mapOf(Session to ledger), ledgerRevision = 1),
            effects = listOf(ContextOptimizationEffect.SaveLedger(Session, ledger, 1)),
        )
        spec.assertTransition(
            from = Ready(ledgers = mapOf(Session to ledger), ledgerRevision = 1),
            intent = Public.RolledOver(Session, Next),
            to = Ready(ledgers = mapOf(Next to ledger), ledgerRevision = 2),
            effects = listOf(
                ContextOptimizationEffect.SaveLedger(Next, ledger, 2),
                ContextOptimizationEffect.SaveLedger(Session, null, 2),
            ),
        )
        spec.assertTransition(
            from = Ready(ledgers = mapOf(Session to ledger), ledgerRevision = 1),
            intent = Public.SessionClosed(Session),
            to = Ready(ledgerRevision = 2),
            effects = listOf(ContextOptimizationEffect.SaveLedger(Session, null, 2)),
        )
    }

    @Test
    fun `stale finishes claims and self rollovers are ignored`() {
        spec.assertIgnored(Ready(), Public.Finished(Session, CompactionResult.Compacted))
        spec.assertIgnored(
            Ready(pending = mapOf(Session to Pending), running = mapOf(Session to Pending)),
            Public.Claim(Session),
        )
        spec.assertIgnored(Ready(), Public.RolledOver(Session, Session))
    }

    @Test
    fun `a failed save is reported`() {
        spec.assertTransition(
            from = Ready(),
            intent = Internal.SaveFailed,
            to = Ready(),
            outputs = listOf(ContextOptimizationOutput.SaveFailed),
        )
    }

    private companion object {
        val Session = SessionRef(EngineId("pi"), SessionSourceId("s"), "one")
        val Next = SessionRef(EngineId("pi"), SessionSourceId("s"), "two")
        val Both = CompactionSupport(isNative = true, isRollover = true)
        val Pending = PendingCompaction(CompactionMethod.Native, CompactionOrigin.User)

        fun usage(percent: Int) = ContextUsage(percent.toLong(), 100)
    }
}
