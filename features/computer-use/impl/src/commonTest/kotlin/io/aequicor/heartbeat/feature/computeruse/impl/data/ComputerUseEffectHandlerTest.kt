package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseBlocker
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseDesktopInput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEffect
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineSpec
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseWindowMode
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.FramePoint
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.VisionBudget
import io.aequicor.heartbeat.feature.computeruse.impl.di.ComputerUseBindings
import io.aequicor.heartbeat.feature.computeruse.impl.di.ComputerUseCoordinatorResources
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.InputInjector
import io.aequicor.heartbeat.feature.computeruse.impl.domain.ScreenPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComputerUseEffectHandlerTest {
    @Test
    fun `disabled master blocks probing and capture effects without touching the host`() = runTest {
        val fixture = fixture()
        fixture.toggles.set(ComputerUseEnabled.key, false)
        fixture.run(ComputerUseEffect.ProbeAvailability)
        assertEquals(
            ComputerUseIntent.Internal.Blocked(listOf(ComputerUseBlocker.UnsupportedPlatform)),
            fixture.feedback.intents.single(),
        )
        fixture.feedback.intents.clear()
        fixture.run(ComputerUseEffect.CaptureFrame(CaptureRequest(), "capture"))
        assertEquals(
            "capture",
            assertIs<ComputerUseIntent.Internal.Rejected>(fixture.feedback.intents.single()).requestId,
        )
        assertEquals(0, fixture.permissions.probes)
        assertEquals(0, fixture.capturer.captures)
        assertTrue(fixture.store.files.isEmpty())
    }

    @Test
    fun `a successful open acknowledges the exact capture session`() = runTest {
        val fixture = fixture()
        fixture.registry.ref!!.states.value = fixture.state.copy(isOpen = false)
        fixture.run(ComputerUseEffect.OpenCapture(ComputerUseMode.Desktop(), Session))
        assertEquals(
            listOf<ComputerUseIntent>(ComputerUseIntent.Internal.CaptureOpened(Session)),
            fixture.feedback.intents,
        )
    }

    @Test
    fun `a revoked screen recording permission refuses the next frame with its correlation id`() = runTest {
        val fixture = fixture()
        fixture.permissions.capabilities = fixture.permissions.capabilities.copy(isCaptureAvailable = false)
        fixture.run(ComputerUseEffect.CaptureFrame(CaptureRequest(), "frame"))
        assertEquals(
            listOf<ComputerUseIntent>(ComputerUseIntent.Internal.Rejected(ComputerUseFailure.PermissionLost, "frame")),
            fixture.feedback.intents,
        )
        assertEquals(0, fixture.capturer.captures)
    }

    @Test
    fun `input effects recheck arming and the current desktop allowance`() = runTest {
        val fixture = fixture()
        fixture.registry.ref!!.states.value = fixture.state.copy(isInputArmed = false)
        fixture.run(ComputerUseEffect.ApplyInput(InputAction.Type("hello"), "unarmed"))
        assertEquals(
            ComputerUseIntent.Internal.Rejected(ComputerUseFailure.NotArmed, "unarmed"),
            fixture.feedback.intents.last(),
        )
        fixture.registry.ref.states.value = fixture.state
        fixture.toggles.set(ComputerUseDesktopInput.key, false)
        fixture.run(ComputerUseEffect.ApplyInput(InputAction.Type("hello"), "blocked"))
        assertEquals(
            ComputerUseIntent.Internal.Rejected(ComputerUseFailure.ModeNotAllowed, "blocked"),
            fixture.feedback.intents.last(),
        )
        assertTrue(fixture.injector.applied.isEmpty())
    }

    @Test
    fun `input effects recheck accessibility immediately before injection`() = runTest {
        val fixture = fixture()
        fixture.permissions.capabilities = fixture.permissions.capabilities.copy(isInputAvailable = false)
        fixture.run(ComputerUseEffect.ApplyInput(InputAction.Type("hello"), "input"))
        assertEquals(
            ComputerUseIntent.Internal.Rejected(ComputerUseFailure.PermissionLost, "input"),
            fixture.feedback.intents.single(),
        )
        assertTrue(fixture.injector.applied.isEmpty())
    }

    @Test
    fun `input waiting behind another operation rechecks arming after acquiring the lock`() = runTest {
        val injector = PausedInputInjector()
        val fixture = fixture(injector)
        val blocking = launch { fixture.coordinator.input(InputAction.Type("blocking")) }
        injector.entered.await()
        val input = launch { fixture.run(ComputerUseEffect.ApplyInput(InputAction.Type("hello"), "queued")) }
        runCurrent()
        fixture.registry.ref!!.states.value = fixture.state.copy(isInputArmed = false)
        injector.proceed.complete(Unit)
        blocking.join()
        input.join()
        assertEquals(listOf<InputAction>(InputAction.Type("blocking")), injector.applied)
        assertEquals(
            ComputerUseIntent.Internal.Rejected(ComputerUseFailure.NotArmed, "queued"),
            fixture.feedback.intents.single(),
        )
    }

    @Test
    fun `queued input rechecks permissions and the desktop input toggle before injection`() = runTest {
        val injector = PausedInputInjector()
        val fixture = fixture(injector)
        val blocking = launch { fixture.coordinator.input(InputAction.Type("blocking")) }
        injector.entered.await()
        val input = launch { fixture.run(ComputerUseEffect.ApplyInput(InputAction.Type("hello"), "queued")) }
        runCurrent()
        fixture.permissions.capabilities = fixture.permissions.capabilities.copy(isInputAvailable = false)
        fixture.toggles.set(ComputerUseDesktopInput.key, false)
        injector.proceed.complete(Unit)
        blocking.join()
        input.join()
        assertEquals(listOf<InputAction>(InputAction.Type("blocking")), injector.applied)
        assertEquals(
            ComputerUseIntent.Internal.Rejected(ComputerUseFailure.PermissionLost, "queued"),
            fixture.feedback.intents.single(),
        )
    }

    @Test
    fun `frame feedback carries the request id and master for later crops`() = runTest {
        val fixture = fixture()
        fixture.run(ComputerUseEffect.CaptureFrame(CaptureRequest(), "frame"))
        val output = assertIs<ComputerUseIntent.Internal.FrameCaptured>(fixture.feedback.intents.single())
        assertEquals("frame", output.requestId)
        assertTrue(output.master.isMaster)
        assertEquals(1, fixture.capturer.captures)
    }

    @Test
    fun `closing a profile purges all its stored frames through application owned cleanup`() = runTest {
        val fixture = fixture()
        fixture.coordinator.capture(CaptureRequest())
        assertTrue(fixture.store.files.isNotEmpty())
        fixture.profile.close()
        runCurrent()
        assertTrue(fixture.store.files.isEmpty())
        assertEquals(null, fixture.coordinator.currentBounds())
    }

    @Test
    fun `late cleanup of a finished session preserves the newer session and acknowledges only the old one`() = runTest {
        val fixture = fixture()
        fixture.coordinator.capture(CaptureRequest())
        val next = CaptureSessionId("next")
        fixture.coordinator.open(next, ComputerUseMode.Desktop())
        fixture.coordinator.capture(CaptureRequest())
        fixture.run(ComputerUseEffect.CloseCapture(Session))
        fixture.run(ComputerUseEffect.PurgeMasters(Session))
        assertTrue(fixture.store.files.isNotEmpty())
        assertTrue(fixture.store.files.keys.all { it.startsWith("next/") })
        assertNotNull(fixture.coordinator.currentBounds())
        assertEquals(
            listOf<ComputerUseIntent>(ComputerUseIntent.Internal.SessionClosed(Session)),
            fixture.completions.intents,
        )
    }

    @Test
    fun `cleanup without a named session leaves the active capture alone`() = runTest {
        val fixture = fixture()
        fixture.coordinator.capture(CaptureRequest())
        fixture.run(ComputerUseEffect.CloseCapture())
        fixture.run(ComputerUseEffect.PurgeMasters())
        assertTrue(fixture.store.files.isNotEmpty())
        assertNotNull(fixture.coordinator.currentBounds())
        assertTrue(fixture.feedback.intents.isEmpty())
        assertTrue(fixture.completions.intents.isEmpty())
    }

    @Test
    fun `a cancelled old cleanup still acknowledges its session after the machine restarts`() = runTest {
        val frames = PausedDeleteFrameStore()
        val fixture = fixture(frames = frames)
        fixture.coordinator.capture(CaptureRequest())
        val expiredFeedback = object : EffectScope<ComputerUseIntent> {
            override suspend fun send(intent: ComputerUseIntent): SendResult = SendResult.Ignored
        }
        val cleanup = launch { fixture.handler.handle(ComputerUseEffect.CloseCapture(Session), expiredFeedback) }
        frames.entered.await()
        cleanup.cancel()
        fixture.completions.states.value = ComputerUseState.Checking
        frames.proceed.complete(Unit)
        cleanup.join()
        assertTrue(frames.files.isEmpty())
        assertEquals(
            listOf<ComputerUseIntent>(ComputerUseIntent.Internal.SessionClosed(Session)),
            fixture.completions.intents,
        )
        assertEquals(ComputerUseState.Checking, fixture.completions.state.value)
        assertEquals(
            listOf<ComputerUseOutput>(ComputerUseOutput.SessionClosed(Session)),
            fixture.completions.emitted,
        )
    }

    @Test
    fun `crop feedback retains the source master when cropping an older cached frame`() = runTest {
        val fixture = fixture()
        val first = assertIs<CaptureOutcome.Produced>(fixture.coordinator.capture(CaptureRequest())).result
        val master = assertNotNull(first.master)
        fixture.coordinator.capture(CaptureRequest())
        fixture.run(ComputerUseEffect.ProduceCrop(CropRequest(master.id, region = CaptureRegion(0, 0, 10, 10)), "crop"))
        val produced = assertIs<ComputerUseIntent.Internal.CropProduced>(fixture.feedback.intents.single())
        assertEquals(master, produced.master)
        assertEquals("crop", produced.requestId)
    }

    private class Feedback : EffectScope<ComputerUseIntent> {
        val intents = mutableListOf<ComputerUseIntent>()
        override suspend fun send(intent: ComputerUseIntent): SendResult {
            intents += intent
            return SendResult.Accepted
        }
    }

    private class PausedInputInjector(private val delegate: FakeInputInjector = FakeInputInjector()) :
        InputInjector by delegate {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val applied: List<InputAction> get() = delegate.applied

        override suspend fun apply(action: InputAction, map: (FramePoint) -> ScreenPoint?): InputOutcome {
            if (!entered.isCompleted) {
                entered.complete(Unit)
                proceed.await()
            }
            return delegate.apply(action, map)
        }
    }

    private class PausedDeleteFrameStore(private val delegate: FakeFrameStore = FakeFrameStore()) :
        FrameStore by delegate {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val files: Map<String, ByteArray> get() = delegate.files

        override suspend fun delete(session: CaptureSessionId) {
            entered.complete(Unit)
            proceed.await()
            delegate.delete(session)
        }
    }

    /** A whole-feature feedback channel resolves against the new state without an exited-effect fence. */
    private class CompletionMachine : Machine<ComputerUseState, ComputerUseIntent, ComputerUseOutput> {
        val states = MutableStateFlow<ComputerUseState>(ComputerUseState.Idle)
        val intents = mutableListOf<ComputerUseIntent>()
        val emitted = mutableListOf<ComputerUseOutput>()
        override val name: String = "computer-use"
        override val state: StateFlow<ComputerUseState> = states
        override val outputs = MutableSharedFlow<ComputerUseOutput>()

        override suspend fun send(intent: ComputerUseIntent): SendResult {
            intents += intent
            val resolution = ComputerUseMachineSpec.resolve(states.value, intent) ?: return SendResult.Ignored
            states.value = resolution.to
            emitted += resolution.outputs
            resolution.outputs.forEach { outputs.emit(it) }
            return SendResult.Accepted
        }
    }

    private class Fixture(
        val handler: ComputerUseEffectHandler,
        val coordinator: CaptureCoordinator,
        val toggles: FakeToggles,
        val permissions: FakeOsPermissions,
        val capturer: FakeScreenCapturer,
        val injector: FakeInputInjector,
        val store: FakeFrameStore,
        val registry: RoutedComputerControlTest.FakeMachineRegistry,
        val profile: TestComputerUseScope,
        val state: ComputerUseState.Capturing,
        val completions: CompletionMachine,
        val feedback: Feedback = Feedback(),
    ) {
        suspend fun run(effect: ComputerUseEffect) = handler.handle(effect, feedback)
    }

    private suspend fun TestScope.fixture(input: InputInjector? = null, frames: FrameStore? = null): Fixture {
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val capturer = FakeScreenCapturer()
        val windows = FakeWindowCatalog(listOf(windowTarget()))
        val injector = FakeInputInjector()
        val encoder = FakeFrameEncoder()
        val store = FakeFrameStore()
        val pipeline = FramePipeline(encoder, frames ?: store, VisionBudget(100_000), dispatchers)
        val cache = MasterFrameCache(encoder, frames ?: store, 8L * 1024 * 1024)
        val resources = ComputerUseCoordinatorResources(
            capturer = capturer,
            windows = windows,
            injector = input ?: injector,
            pipeline = pipeline,
            cache = cache,
            dispatchers = dispatchers,
        )
        val profile = TestComputerUseScope(backgroundScope)
        val coordinator = ComputerUseBindings.coordinator(resources, profile, TestComputerUseScope(backgroundScope))
        coordinator.open(Session, ComputerUseMode.Desktop())
        val permissions = FakeOsPermissions()
        val toggles = FakeToggles(
            mapOf(
                ComputerUseEnabled.key to true,
                ComputerUseDesktopInput.key to true,
                ComputerUseWindowMode.key to true,
            ),
        )
        val registry = RoutedComputerControlTest.FakeMachineRegistry(RoutedComputerControlTest.FakeMachineRef())
        val state = ComputerUseState.Capturing(
            Session,
            ComputerUseMode.Desktop(),
            CaptureOwner.Panel,
            ComputerUseCapabilities(true, true, true, true),
            isInputArmed = true,
            isOpen = true,
        )
        registry.ref!!.states.value = state
        val access = ComputerUseAccess(toggles, permissions, registry, dispatchers)
        val completions = CompletionMachine()
        return Fixture(
            ComputerUseEffectHandler(
                access,
                coordinator,
                ComputerUseCaptureExecutor(coordinator, access, NoNativeControlRouter()),
                lazy { completions },
            ),
            coordinator, toggles, permissions,
            capturer, injector, store, registry, profile, state, completions,
        )
    }

    private companion object {
        val Session = CaptureSessionId("effect")
    }
}
