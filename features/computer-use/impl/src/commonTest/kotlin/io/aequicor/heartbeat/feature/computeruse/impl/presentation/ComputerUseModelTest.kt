package io.aequicor.heartbeat.feature.computeruse.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.impl.data.TestDispatchers
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUseSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias SettingsProvider =
    Provider<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>

class ComputerUseModelTest {
    @Test
    fun `opening settings reflects saved preference without starting capture`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Idle, isEnabled = true)
        val screen = fixture.subscribe()
        assertTrue(screen.states.value.isEnabled)
        assertTrue(screen.states.value.isLoaded)
        assertEquals(emptyList(), fixture.machine.sent)
    }

    @Test
    fun `enabling the tool saves preference without choosing or starting capture`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Idle)
        val screen = fixture.subscribe()
        screen.intent(ComputerUseScreenIntent.SetEnabled(true))
        runCurrent()
        assertTrue(fixture.preferences.read().isEnabled)
        assertTrue(screen.states.value.isEnabled)
        assertEquals(emptyList(), fixture.machine.sent)
        assertEquals(listOf("save:true"), fixture.events)
    }

    @Test
    fun `disabling an active capture revokes it before saving preference`() = runTest {
        val fixture = Fixture(this, capturing(), isEnabled = true)
        val screen = fixture.subscribe()
        screen.intent(ComputerUseScreenIntent.SetEnabled(false))
        runCurrent()
        assertEquals(listOf<ComputerUseIntent>(ComputerUseIntent.Public.Revoke), fixture.machine.sent)
        assertEquals(listOf("machine:Revoke", "save:false"), fixture.events)
        assertFalse(fixture.preferences.read().isEnabled)
        assertFalse(screen.states.value.isEnabled)
    }

    @Test
    fun `failed disable persistence still revokes the active capture`() = runTest {
        val fixture = Fixture(this, capturing(), isEnabled = true)
        val screen = fixture.subscribe()
        fixture.preferences.failSave = true
        screen.intent(ComputerUseScreenIntent.SetEnabled(false))
        runCurrent()
        assertEquals(listOf<ComputerUseIntent>(ComputerUseIntent.Public.Revoke), fixture.machine.sent)
        assertEquals(SettingsError.SaveFailed, screen.states.value.error)
        assertTrue(screen.states.value.isEnabled)
    }

    @Test
    fun `failed enable persistence keeps the switch off and reports the save failure`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Idle)
        val screen = fixture.subscribe()
        fixture.preferences.failSave = true
        screen.intent(ComputerUseScreenIntent.SetEnabled(true))
        runCurrent()
        assertFalse(fixture.preferences.read().isEnabled)
        assertFalse(screen.states.value.isEnabled)
        assertTrue(screen.states.value.isLoaded)
        assertEquals(SettingsError.SaveFailed, screen.states.value.error)
        assertEquals(emptyList(), fixture.machine.sent)
    }

    @Test
    fun `successful retry clears the save failure`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Idle)
        val screen = fixture.subscribe()
        fixture.preferences.failSave = true
        screen.intent(ComputerUseScreenIntent.SetEnabled(true))
        runCurrent()
        assertEquals(SettingsError.SaveFailed, screen.states.value.error)
        fixture.preferences.failSave = false
        screen.intent(ComputerUseScreenIntent.SetEnabled(true))
        runCurrent()
        assertNull(screen.states.value.error)
        assertTrue(screen.states.value.isEnabled)
        assertTrue(fixture.preferences.read().isEnabled)
    }

    @Test
    fun `failed preference read keeps the switch locked and reports a load failure`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Idle, isEnabled = true, failObserve = true)
        val screen = fixture.subscribe()
        assertFalse(screen.states.value.isLoaded)
        assertFalse(screen.states.value.isEnabled)
        assertEquals(SettingsError.LoadFailed, screen.states.value.error)
    }

    @Test
    fun `a failed read stays visible through later save failures and successes`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Idle, isEnabled = true, failObserve = true)
        val screen = fixture.subscribe()
        fixture.preferences.failSave = true
        screen.intent(ComputerUseScreenIntent.SetEnabled(false))
        runCurrent()
        assertEquals(SettingsError.LoadFailed, screen.states.value.error)
        fixture.preferences.failSave = false
        screen.intent(ComputerUseScreenIntent.SetEnabled(false))
        runCurrent()
        assertEquals(SettingsError.LoadFailed, screen.states.value.error)
    }

    @Test
    fun `closing settings right after disabling still revokes and saves the opt out`() = runTest {
        val fixture = Fixture(this, capturing(), isEnabled = true)
        val screen = fixture.subscribe()
        val saving = CompletableDeferred<Unit>()
        fixture.preferences.saveGate = saving
        screen.intent(ComputerUseScreenIntent.SetEnabled(false))
        runCurrent()
        assertEquals(listOf("machine:Revoke", "save:false"), fixture.events)
        fixture.closeScreen()
        saving.complete(Unit)
        runCurrent()
        assertFalse(fixture.preferences.read().isEnabled)
    }

    @Test
    fun `saving enabled switch keeps an existing agent capture unchanged`() = runTest {
        val active = capturing()
        val fixture = Fixture(this, active, isEnabled = true)
        val screen = fixture.subscribe()
        screen.intent(ComputerUseScreenIntent.SetEnabled(true))
        runCurrent()
        assertEquals(emptyList(), fixture.machine.sent)
        assertEquals(active, fixture.machine.state.value)
    }

    @Test
    fun `host permission changes are reflected alongside the tool switch`() = runTest {
        val fixture = Fixture(
            this,
            ComputerUseState.Unavailable(listOf(ComputerUseBlocker.ScreenRecordingPermission)),
        )
        val screen = fixture.subscribe()
        assertEquals(listOf(BlockerUi.ScreenRecordingPermission), screen.states.value.blockers)
        fixture.machine.state.value = ComputerUseState.Ready(capabilities)
        runCurrent()
        assertEquals(emptyList(), screen.states.value.blockers)
    }

    @Test
    fun `available host still reports permissions that block agent input`() = runTest {
        val inputBlocked = capabilities.copy(
            isInputAvailable = false,
            blockers = listOf(ComputerUseBlocker.AccessibilityPermission),
        )
        val fixture = Fixture(this, ComputerUseState.Ready(inputBlocked), isEnabled = true)
        val screen = fixture.subscribe()
        assertEquals(listOf(BlockerUi.AccessibilityPermission), screen.states.value.blockers)
        fixture.machine.state.value = capturing(inputBlocked)
        runCurrent()
        assertEquals(listOf(BlockerUi.AccessibilityPermission), screen.states.value.blockers)
        fixture.machine.state.value = ComputerUseState.Ready(capabilities)
        runCurrent()
        assertEquals(emptyList(), screen.states.value.blockers)
    }

    @Test
    fun `saved switch changes are reflected while settings are open`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Idle)
        val screen = fixture.subscribe()
        fixture.preferences.setEnabled(true)
        runCurrent()
        assertTrue(screen.states.value.isEnabled)
        fixture.preferences.setEnabled(false)
        runCurrent()
        assertFalse(screen.states.value.isEnabled)
    }

    private class Fixture(
        private val scope: TestScope,
        initial: ComputerUseState,
        isEnabled: Boolean = false,
        failObserve: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val machine = FakeSettingsMachine(initial, events)
        val preferences = FakeSettingsPreferences(isEnabled, events, failObserve)
        private val screenJob = Job(scope.backgroundScope.coroutineContext.job)
        private val model = ComputerUseModel(
            machine,
            preferences,
            HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
            scope.backgroundScope + screenJob,
        )

        /** Ends the screen scope the way closing settings does. */
        fun closeScreen() = screenJob.cancel()

        suspend fun subscribe(): SettingsProvider {
            val provider = CompletableDeferred<SettingsProvider>()
            scope.backgroundScope.launch {
                model.store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            return provider.await()
        }
    }
}

private val capabilities = ComputerUseCapabilities(true, true, true, true)

private fun capturing(granted: ComputerUseCapabilities = capabilities) = ComputerUseState.Capturing(
    CaptureSessionId("session"),
    ComputerUseMode.Desktop(),
    CaptureOwner.Panel,
    granted,
    isOpen = true,
)

private class FakeSettingsMachine(initial: ComputerUseState, private val events: MutableList<String>) :
    Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput> {
    override val name = "computer-use"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ComputerUseOutput>(extraBufferCapacity = 8)
    val sent = mutableListOf<ComputerUseIntent>()

    override suspend fun send(intent: ComputerUseIntent): SendResult = SendResult.Accepted.also {
        sent += intent
        events += "machine:${intent::class.simpleName}"
    }
}

private class FakeSettingsPreferences(
    isEnabled: Boolean,
    private val events: MutableList<String>,
    private val failObserve: Boolean,
) : ComputerUsePreferences {
    private val current = MutableStateFlow(ComputerUseSettings(isEnabled = isEnabled))
    var failSave = false
    var saveGate: CompletableDeferred<Unit>? = null
    override suspend fun read() = current.value
    override fun observe(): Flow<ComputerUseSettings> = if (failObserve) {
        flow { error("storage unavailable") }
    } else {
        current
    }
    override suspend fun setEnabled(isEnabled: Boolean) {
        events += "save:$isEnabled"
        saveGate?.await()
        check(!failSave) { "storage unavailable" }
        current.value = current.value.copy(isEnabled = isEnabled)
    }
    override suspend fun setPreset(name: String): Boolean {
        current.value = current.value.copy(preset = name)
        return true
    }
    override suspend fun setCursorIncluded(isIncluded: Boolean) {
        current.value = current.value.copy(isCursorIncluded = isIncluded)
    }
}
