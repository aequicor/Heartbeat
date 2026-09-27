package io.aequicor.heartbeat.feature.togglespanel.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggleControl
import io.aequicor.heartbeat.core.featuretoggles.ToggleSource
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.togglespanel.api.ToggleOperation
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelEffect
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelIntent
import io.aequicor.heartbeat.feature.togglespanel.impl.data.LocalTogglesRepository
import io.aequicor.heartbeat.feature.togglespanel.impl.domain.TogglesPanelEffects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TogglesPanelEffectsTest {
    private val flag = FeatureToggle.Flag("test.flag", "Flag")
    private val choice = FeatureToggle.Choice("test.mode", "Mode", listOf("One", "Two"))
    private val control = FakeControl()
    private val effects = TogglesPanelEffects(LocalTogglesRepository(control))
    private val events = mutableListOf<TogglesPanelIntent>()
    private val feedback = object : EffectScope<TogglesPanelIntent> {
        override suspend fun send(intent: TogglesPanelIntent): SendResult {
            events += intent
            return SendResult.Accepted
        }
    }

    @Test
    fun `observation continues during a suspended write`() = runTest {
        val observer = backgroundScope.launch { effects.handle(TogglesPanelEffect.Observe, feedback) }
        runCurrent()
        control.gate = CompletableDeferred()
        val writer = launch { effects.handle(TogglesPanelEffect.Write(ToggleOperation.SetFlag(flag, true)), feedback) }
        runCurrent()
        val rows = listOf(ToggleState(flag, true, ToggleSource.LocalOverride))
        control.states.value = rows
        runCurrent()
        assertTrue(observer.isActive)
        assertTrue(writer.isActive)
        assertEquals(TogglesPanelIntent.Internal.Snapshot(rows), events.last())
        control.gate?.complete(Unit)
        writer.join()
        assertEquals(TogglesPanelIntent.Internal.Written, events.last())
    }

    @Test
    fun `each operation delegates to the controller and acknowledges success`() = runTest {
        val operations = listOf(
            ToggleOperation.SetFlag(flag, true),
            ToggleOperation.SetChoice(choice, "Two"),
            ToggleOperation.Reset(flag),
            ToggleOperation.ResetAll,
        )
        operations.forEach { effects.handle(TogglesPanelEffect.Write(it), feedback) }
        assertEquals(listOf("test.flag=true", "test.mode=Two", "reset:test.flag", "reset-all"), control.writes)
        assertEquals(List<TogglesPanelIntent>(4) { TogglesPanelIntent.Internal.Written }, events)
    }

    @Test
    fun `read and write failures propagate to the machine without false acknowledgement`() = runTest {
        control.fail = true
        assertFailsWith<IllegalStateException> { effects.handle(TogglesPanelEffect.Observe, feedback) }
        assertFailsWith<IllegalStateException> {
            effects.handle(TogglesPanelEffect.Write(ToggleOperation.ResetAll), feedback)
        }
        assertTrue(events.isEmpty())
    }

    private class FakeControl : FeatureToggleControl {
        override val registered = emptyList<FeatureToggle<*>>()
        val states = MutableStateFlow<List<ToggleState<*>>>(emptyList())
        val writes = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        override fun observeStates() = flow {
            check(!fail) { "read failed" }
            states.collect { emit(it) }
        }
        override suspend fun <T : Any> setOverride(toggle: FeatureToggle<T>, value: T) = write("${toggle.key}=$value")
        override suspend fun reset(toggle: FeatureToggle<*>) = write("reset:${toggle.key}")
        override suspend fun resetAll() = write("reset-all")
        private suspend fun write(operation: String) {
            gate?.await()
            check(!fail) { "write failed" }
            writes += operation
        }
    }
}
