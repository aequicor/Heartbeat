package io.aequicor.heartbeat.feature.computeruse.impl.presentation

import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.EncodedFrame
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.ScreenBounds
import io.aequicor.heartbeat.feature.computeruse.api.WindowId
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import io.aequicor.heartbeat.feature.computeruse.impl.data.TestDispatchers
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUsePreferences
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ComputerUseSettings
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pro.respawn.flowmvi.api.Provider
import pro.respawn.flowmvi.dsl.collect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private typealias PanelProvider =
    Provider<ComputerUseScreenState, ComputerUseScreenIntent, ComputerUseScreenAction>

class ComputerUseModelTest {
    @Test
    fun `retry after revoke probes from Idle without reopening the panel`() = runTest {
        val fixture = Fixture(this, capturing())
        val screen = fixture.subscribe()
        screen.intent(ComputerUseScreenIntent.Revoke)
        runCurrent()
        assertEquals(ComputerUseIntent.Public.Revoke, fixture.machine.sent.single())
        fixture.machine.state.value = ComputerUseState.Idle
        runCurrent()
        screen.intent(ComputerUseScreenIntent.Retry)
        runCurrent()
        assertEquals(ComputerUseIntent.Public.Start, fixture.machine.sent.last())
        assertEquals(PhaseUi.Idle, screen.states.value.phase)
    }

    @Test
    fun `mode and window selection switch the active capture immediately`() = runTest {
        val fixture = Fixture(this, capturing())
        val screen = fixture.subscribe()
        screen.intent(ComputerUseScreenIntent.SelectMode(ModeUi.Window))
        runCurrent()
        val first = assertIs<ComputerUseIntent.Public.SwitchMode>(fixture.machine.sent.single())
        assertEquals(windows.first(), assertIs<ComputerUseMode.Window>(first.mode).target)
        assertEquals(CaptureOwner.Panel, first.owner)
        assertEquals(capturing().session, first.expectedSession)
        screen.intent(ComputerUseScreenIntent.SelectWindow(windows.last().id.value))
        runCurrent()
        val second = assertIs<ComputerUseIntent.Public.SwitchMode>(fixture.machine.sent.last())
        assertEquals(windows.last(), assertIs<ComputerUseMode.Window>(second.mode).target)
    }

    @Test
    fun `selecting the active mode or target keeps its capture session intact`() = runTest {
        val fixture = Fixture(this, capturing().copy(mode = ComputerUseMode.Window(windows.first())))
        val screen = fixture.subscribe()
        screen.intent(ComputerUseScreenIntent.SelectMode(ModeUi.Window))
        screen.intent(ComputerUseScreenIntent.SelectWindow(windows.first().id.value))
        runCurrent()
        assertEquals(emptyList(), fixture.machine.sent)
    }

    @Test
    fun `opening an existing session loads its frame without a replayed output`() = runTest {
        val capture = frame()
        val fixture = Fixture(this, capturing().copy(lastPreview = capture, frameCount = 1))
        val screen = fixture.subscribe()
        assertEquals(capture.id.value, screen.states.value.frame?.id)
        assertContentEquals(byteArrayOf(1, 2), screen.states.value.frame?.content)
        assertEquals(listOf(capture.path), fixture.frames.reads)
    }

    @Test
    fun `closing an old session does not clear the active session preview`() = runTest {
        val capture = frame()
        val fixture = Fixture(this, capturing().copy(lastPreview = capture))
        val screen = fixture.subscribe()
        fixture.machine.outputs.emit(ComputerUseOutput.SessionClosed(CaptureSessionId("old-session")))
        runCurrent()
        assertEquals(capture.id.value, screen.states.value.frame?.id)
        assertContentEquals(byteArrayOf(1, 2), screen.states.value.frame?.content)
    }

    @Test
    fun `ending or switching a capture clears the old frame and action journal`() = runTest {
        val fixture = Fixture(this, capturing().copy(lastPreview = frame()))
        val screen = fixture.subscribe()
        fixture.machine.outputs.emit(ComputerUseOutput.InputApplied(InputAction.Type("private")))
        runCurrent()
        assertEquals(JournalEntryUi(JournalKindUi.Type, count = 7), screen.states.value.journal.single())
        fixture.machine.state.value = capturing().copy(session = CaptureSessionId("replacement"))
        runCurrent()
        assertNull(screen.states.value.frame)
        assertEquals(emptyList(), screen.states.value.journal)
        fixture.machine.state.value = capturing().copy(lastPreview = frame())
        runCurrent()
        fixture.machine.state.value = ComputerUseState.Ready(capabilities, windows)
        runCurrent()
        assertNull(screen.states.value.frame)
        assertEquals(0L, screen.states.value.frameCount)
    }

    @Test
    fun `a suspended frame read is cancelled when the session is revoked`() = runTest {
        val fixture = Fixture(this, capturing().copy(lastPreview = frame()))
        val pending = CompletableDeferred<ByteArray?>()
        fixture.frames.pending = pending
        val screen = fixture.subscribe()
        fixture.machine.state.value = ComputerUseState.Idle
        runCurrent()
        pending.complete(byteArrayOf(3))
        runCurrent()
        assertNull(screen.states.value.frame)
    }

    private class Fixture(private val scope: TestScope, initial: ComputerUseState) {
        val machine = FakePanelMachine(initial)
        val frames = FakePanelFrames()
        private val model = ComputerUseModel(
            machine,
            frames,
            FakePanelPreferences(),
            HeartbeatStoreFactory(TestDispatchers(StandardTestDispatcher(scope.testScheduler))),
            scope.backgroundScope,
        )

        suspend fun subscribe(): PanelProvider {
            val provider = CompletableDeferred<PanelProvider>()
            scope.backgroundScope.launch {
                model.store.collect {
                    provider.complete(this)
                    awaitCancellation()
                }
            }
            scope.runCurrent()
            machine.sent.clear()
            return provider.await()
        }
    }
}

private val capabilities = ComputerUseCapabilities(true, true, true, true)
private val windows = listOf(
    WindowTarget(WindowId("a"), "A", "Window A", ScreenBounds(0, 0, 200, 100)),
    WindowTarget(WindowId("b"), "B", "Window B", ScreenBounds(0, 0, 200, 100)),
)

private fun capturing() = ComputerUseState.Capturing(
    CaptureSessionId("session"),
    ComputerUseMode.Desktop(),
    CaptureOwner.Panel,
    capabilities,
    windows,
    isOpen = true,
)

private fun frame() = CaptureRef(
    CaptureId("frame"), CaptureSessionId("session"), CaptureFormat.Png, 200, 100, CaptureRegion(0, 0, 200, 100),
    200, 100, 2, 100, "stored-frame", 1,
)

private class FakePanelMachine(initial: ComputerUseState) :
    Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput> {
    override val name = "computer-use"
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ComputerUseOutput>(extraBufferCapacity = 8)
    val sent = mutableListOf<ComputerUseIntent>()

    override suspend fun send(intent: ComputerUseIntent): SendResult = SendResult.Accepted.also { sent += intent }
}

private class FakePanelFrames : FrameStore {
    val reads = mutableListOf<String>()
    var pending: CompletableDeferred<ByteArray?>? = null

    override suspend fun read(path: String): ByteArray? {
        reads += path
        return pending?.await() ?: byteArrayOf(1, 2)
    }

    override suspend fun write(session: CaptureSessionId, id: CaptureId, frame: EncodedFrame): String = "frame"

    override suspend fun delete(session: CaptureSessionId) = Unit
}

private class FakePanelPreferences : ComputerUsePreferences {
    private val current = MutableStateFlow(ComputerUseSettings())
    override suspend fun read() = current.value
    override fun observe() = current
    override suspend fun setEnabled(isEnabled: Boolean) {
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
