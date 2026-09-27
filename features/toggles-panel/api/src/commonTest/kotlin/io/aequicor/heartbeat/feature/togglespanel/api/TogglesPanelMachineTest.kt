package io.aequicor.heartbeat.feature.togglespanel.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TogglesPanelMachineTest {
    private val flag = FeatureToggle.Flag("test.flag", "Flag")
    private val choice = FeatureToggle.Choice("test.mode", "Mode", listOf("a", "b"))
    private val rows = listOf(ToggleState(flag, false, ToggleSource.Default))
    private val ready = TogglesPanelState.Active(rows)

    @Test
    fun `start and retry establish observation`() {
        TogglesPanelMachineSpec.assertTransition(
            TogglesPanelState.Idle,
            TogglesPanelIntent.Public.Start,
            TogglesPanelState.Active(),
            effects = listOf(TogglesPanelEffect.Observe),
        )
        TogglesPanelMachineSpec.assertTransition(
            TogglesPanelState.LoadError,
            TogglesPanelIntent.Public.RetryLoad,
            TogglesPanelState.Active(),
            effects = listOf(TogglesPanelEffect.Observe),
        )
        TogglesPanelMachineSpec.assertTransition(
            ready,
            TogglesPanelIntent.Internal.ObserveFailed,
            TogglesPanelState.LoadError,
        )
    }

    @Test
    fun `every mutation preserves the ongoing observation and serializes writes`() {
        val operations = listOf(
            ToggleOperation.SetFlag(flag, true),
            ToggleOperation.SetChoice(choice, "b"),
            ToggleOperation.Reset(flag),
            ToggleOperation.ResetAll,
        )
        operations.forEach { operation ->
            val pending = ready.copy(pending = operation)
            val change = requireNotNull(
                TogglesPanelMachineSpec.resolve(ready, TogglesPanelIntent.Public.Apply(operation)),
            )
            assertFalse(change.isStateChange, "goto would cancel the observer")
            assertEquals(pending, change.to)
            assertEquals(listOf(TogglesPanelEffect.Write(operation)), change.effects)
            TogglesPanelMachineSpec.assertIgnored(pending, TogglesPanelIntent.Public.Apply(operation))
            TogglesPanelMachineSpec.assertTransition(pending, TogglesPanelIntent.Internal.Written, ready)
            val snapshot = requireNotNull(
                TogglesPanelMachineSpec.resolve(pending, TogglesPanelIntent.Internal.Snapshot(rows)),
            )
            assertFalse(snapshot.isStateChange)
            assertEquals(operation, (snapshot.to as TogglesPanelState.Active).pending)
        }
    }

    @Test
    fun `write failures retain rows and retry only on explicit request`() {
        val operation = ToggleOperation.SetFlag(flag, true)
        val failed = ready.copy(failed = operation)
        TogglesPanelMachineSpec.assertTransition(
            ready.copy(pending = operation),
            TogglesPanelIntent.Internal.WriteFailed,
            failed,
        )
        TogglesPanelMachineSpec.assertTransition(
            failed,
            TogglesPanelIntent.Public.RetryWrite,
            ready.copy(pending = operation),
            effects = listOf(TogglesPanelEffect.Write(operation)),
        )
        TogglesPanelMachineSpec.assertTransition(failed, TogglesPanelIntent.Public.DismissError, ready)
        TogglesPanelMachineSpec.assertIgnored(ready, TogglesPanelIntent.Public.RetryWrite)
        assertNull(TogglesPanelMachineSpec.persistence)
    }

    @Test
    fun `loading ignores changes and failures map to recoverable states`() {
        TogglesPanelMachineSpec.assertIgnored(
            TogglesPanelState.Active(),
            TogglesPanelIntent.Public.Apply(ToggleOperation.ResetAll),
        )
        val error = IllegalStateException("storage")
        assertEquals(
            TogglesPanelIntent.Internal.ObserveFailed,
            TogglesPanelMachineSpec.onEffectFailure(TogglesPanelEffect.Observe, error),
        )
        assertEquals(
            TogglesPanelIntent.Internal.WriteFailed,
            TogglesPanelMachineSpec.onEffectFailure(TogglesPanelEffect.Write(ToggleOperation.ResetAll), error),
        )
    }
}
