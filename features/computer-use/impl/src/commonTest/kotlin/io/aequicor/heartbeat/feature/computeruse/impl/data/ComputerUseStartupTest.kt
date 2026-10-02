package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineSpec
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.impl.di.ComputerUseStartup
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ComputerUseStartupTest {
    @Test
    fun `profile switch controls capture lifetime even without an open settings screen`() = runTest {
        val preferences = FakeComputerUsePreferences(isEnabled = false)
        val toggles = FakeToggles(mapOf(ComputerUseEnabled.key to true))
        val recording = StartupMachine()
        val machine = lazy<Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>> { recording }
        ComputerUseStartup(machine, toggles, lazy { preferences }, TestComputerUseScope(backgroundScope)).start()
        runCurrent()
        assertFalse(machine.isInitialized())
        preferences.setEnabled(true)
        runCurrent()
        assertEquals(ComputerUseIntent.Public.Start, recording.sent.single())
        recording.state.value = ComputerUseState.Capturing(
            CaptureSessionId("active"),
            ComputerUseMode.Desktop(),
            CaptureOwner.Panel,
            ComputerUseCapabilities(true, true, true, true),
            isInputArmed = true,
            isOpen = true,
        )
        preferences.setEnabled(false)
        runCurrent()
        assertEquals(ComputerUseIntent.Public.Revoke, recording.sent.last())
        assertEquals(ComputerUseState.Idle, recording.state.value)
    }

    @Test
    fun `profile opt in cannot start capture while the rollout flag is disabled`() = runTest {
        val preferences = lazy { FakeComputerUsePreferences(isEnabled = true) }
        val machine = lazy<Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput>> { StartupMachine() }
        ComputerUseStartup(machine, FakeToggles(), preferences, TestComputerUseScope(backgroundScope)).start()
        runCurrent()
        assertFalse(machine.isInitialized())
        assertFalse(preferences.isInitialized())
    }
}

private class StartupMachine : Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput> {
    override val name = "computer-use"
    override val state = MutableStateFlow<ComputerUseState>(ComputerUseState.Idle)
    override val outputs = MutableSharedFlow<ComputerUseOutput>()
    val sent = mutableListOf<ComputerUseIntent>()
    override suspend fun send(intent: ComputerUseIntent): SendResult {
        sent += intent
        val transition = ComputerUseMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = transition.to
        return SendResult.Accepted
    }
}
